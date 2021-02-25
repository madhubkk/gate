/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.gate.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.netflix.spinnaker.fiat.model.UserPermission
import com.netflix.spinnaker.fiat.model.resources.Role
import com.netflix.spinnaker.fiat.shared.FiatPermissionEvaluator
import com.netflix.spinnaker.fiat.shared.FiatService
import com.netflix.spinnaker.fiat.shared.FiatStatus
import com.netflix.spinnaker.gate.security.SpinnakerUser
import com.netflix.spinnaker.gate.services.commands.HystrixFactory
import com.netflix.spinnaker.gate.services.internal.ExtendedFiatService
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.exceptions.SpinnakerException
import com.netflix.spinnaker.kork.exceptions.SystemException
import com.netflix.spinnaker.security.AuthenticatedRequest
import com.netflix.spinnaker.security.User
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import retrofit.RetrofitError

import javax.annotation.Nonnull
import java.time.Duration

import static com.netflix.spinnaker.gate.retrofit.UpstreamBadRequest.classifyError

@Slf4j
@Component
@EnableFeignClients
class PermissionService {

  static final String HYSTRIX_GROUP = "permission"

  @Autowired
  FiatService fiatService

  @Autowired
  ExtendedFiatService extendedFiatService

  @Autowired
  ServiceAccountFilterConfigProps serviceAccountFilterConfigProps

  @Autowired
  @Qualifier("fiatLoginService")
  Optional<FiatService> fiatLoginService

  @Autowired
  FiatPermissionEvaluator permissionEvaluator

  @Autowired
  FiatStatus fiatStatus

  @Value('${services.platform.enabled}')
  boolean isOesAuthorizationServiceEnabled

  @Autowired
  OesAuthorizationService oesAuthorizationService

  Gson gson = new Gson()

  boolean isEnabled() {
    return fiatStatus.isEnabled()
  }

  private FiatService getFiatServiceForLogin() {
    return fiatLoginService.orElse(fiatService);
  }

  void login(String userId) {
    if (fiatStatus.isEnabled()) {
      HystrixFactory.newVoidCommand(HYSTRIX_GROUP, "login") {
        try {
          AuthenticatedRequest.allowAnonymous({
            fiatServiceForLogin.loginUser(userId, "")
            permissionEvaluator.invalidatePermission(userId)
          })
        } catch (RetrofitError e) {
          throw classifyError(e)
        }
      }.execute()
    }
  }

  void loginWithRoles(String userId, Collection<String> roles) {
    if (fiatStatus.isEnabled()) {
      HystrixFactory.newVoidCommand(HYSTRIX_GROUP, "loginWithRoles") {
        try {
          AuthenticatedRequest.allowAnonymous({
            fiatServiceForLogin.loginWithRoles(userId, roles)
            permissionEvaluator.invalidatePermission(userId)
          })
        } catch (RetrofitError e) {
          throw classifyError(e)
        }
      }.execute()
    }
    if (isOesAuthorizationServiceEnabled){
      try {
        JsonObject body = new JsonObject()
        String groups = gson.toJson(roles, Collection.class)
        body.addProperty("groups", groups)
        oesAuthorizationService.importAndCacheUserGroups(body)
      } catch(RetrofitError e){
        log.error("Exception occured while login with roles : {}", e)
        throw classifyError(e)
      } catch(Exception e1){
        log.error("Exception occured while login with roles : {}", e)
      }
    }

  }

  void logout(String userId) {
    if (fiatStatus.isEnabled()) {
      HystrixFactory.newVoidCommand(HYSTRIX_GROUP, "logout") {
        try {
          fiatServiceForLogin.logoutUser(userId)
          permissionEvaluator.invalidatePermission(userId)
        } catch (RetrofitError e) {
          throw classifyError(e)
        }
      }.execute()
    }
  }

  void sync() {
    if (fiatStatus.isEnabled()) {
      HystrixFactory.newVoidCommand(HYSTRIX_GROUP, "sync") {
        try {
          fiatServiceForLogin.sync(Collections.emptyList())
        } catch (RetrofitError e) {
          throw classifyError(e)
        }
      }.execute()
    }
  }

  Set<Role> getRoles(String userId) {
    if (!fiatStatus.isEnabled()) {
      return []
    }
    return HystrixFactory.newListCommand(HYSTRIX_GROUP, "getRoles") {
      try {
        return permissionEvaluator.getPermission(userId)?.roles ?: []
      } catch (RetrofitError e) {
        throw classifyError(e)
      }
    }.execute() as Set<Role>
  }

  //VisibleForTesting
  @PackageScope List<UserPermission.View> lookupServiceAccounts(String userId) {
    try {
      return extendedFiatService.getUserServiceAccounts(userId)
    } catch (RetrofitError re) {
      boolean notFound = re.response?.status == HttpStatus.NOT_FOUND.value()
      if (notFound) {
        return []
      }
      boolean shouldRetry = re.response == null || HttpStatus.valueOf(re.response.status).is5xxServerError()
      throw new SystemException("getUserServiceAccounts failed", re).setRetryable(shouldRetry)
    }
  }

  List<String> getServiceAccountsForApplication(@SpinnakerUser User user, @Nonnull String application) {
    if (!serviceAccountFilterConfigProps.enabled ||
        !user ||
        !application ||
        !fiatStatus.enabled ||
        serviceAccountFilterConfigProps.matchAuthorizations.isEmpty()) {
      return getServiceAccounts(user);
    }

    List<String> filteredServiceAccounts
    RetrySupport retry = new RetrySupport()
    try {
      List<UserPermission.View> serviceAccounts = retry.retry({ lookupServiceAccounts(user.username) }, 3, Duration.ofMillis(50), false)

      filteredServiceAccounts = serviceAccounts.findResults {
        if (it.applications.find { it.name.equalsIgnoreCase(application) && it.authorizations.find { serviceAccountFilterConfigProps.matchAuthorizations.contains(it) } }) {
          return it.name
        }
        return null
      }
    } catch (SpinnakerException se) {
      log.error("failed to lookup user {} service accounts for application {}, falling back to all user service accounts", user, application, se)
      return getServiceAccounts(user)
    }

    // if there are no service accounts for the requested application, fall back to the full list of service accounts for the user
    //  to avoid a chicken and egg problem of trying to enable security for the first time on an application
    return filteredServiceAccounts ?: getServiceAccounts(user)
  }

  List<String> getServiceAccounts(@SpinnakerUser User user) {

    if (!user) {
      log.debug("getServiceAccounts: Spinnaker user is null.")
      return []
    }

    if (!fiatStatus.isEnabled()) {
      log.debug("getServiceAccounts: Fiat disabled.")
      return []
    }

    return HystrixFactory.newListCommand(HYSTRIX_GROUP, "getServiceAccounts") {
      try {
        UserPermission.View view = permissionEvaluator.getPermission(user.username)
        return view.getServiceAccounts().collect { it.name }
      } catch (RetrofitError re) {
        throw classifyError(re)
      }
    }.execute() as List<String>
  }

  boolean isAdmin(String userId) {
    return permissionEvaluator.getPermission(userId)?.isAdmin()
  }
}
