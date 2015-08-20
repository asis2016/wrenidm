/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2015 ForgeRock AS
 */

package org.forgerock.openidm.jaspi.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;

import org.forgerock.guava.common.base.Function;
import org.forgerock.guava.common.base.Predicate;
import org.forgerock.guava.common.collect.FluentIterable;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.ConnectionFactory;
import org.forgerock.json.resource.ForbiddenException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.services.context.SecurityContext;
import org.forgerock.services.context.Context;
import org.forgerock.json.resource.SingletonResourceProvider;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.json.resource.http.HttpContext;
import org.forgerock.openidm.config.enhanced.EnhancedConfig;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.jaspi.config.AuthenticationConfig;
import org.forgerock.util.promise.Promise;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.forgerock.json.resource.Responses.newActionResponse;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.AUTHENTICATION_ID;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.PROPERTY_MAPPING;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.QUERY_ID;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.QUERY_ON_RESOURCE;
import static org.forgerock.openidm.jaspi.modules.IDMJaspiModuleWrapper.USER_CREDENTIAL;

/**
 * An implementation of the AuthenticationConfig that reads and holds the authentication configuration.
 */
@Component(name = AuthenticationService.PID, immediate = true, policy = ConfigurationPolicy.REQUIRE)
@Service
@Properties({
        @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
        @Property(name = Constants.SERVICE_DESCRIPTION, value = "OpenIDM Authentication Service"),
        @Property(name = ServerConstants.ROUTER_PREFIX, value = "/authentication")
})
public class AuthenticationService implements AuthenticationConfig, SingletonResourceProvider {

    /** The PID for this Component. */
    public static final String PID = "org.forgerock.openidm.authentication";

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    /** Re-authentication password header. */
    private static final String HEADER_REAUTH_PASSWORD = "X-OpenIDM-Reauth-Password";

    // note: 'static final' required to avoid error with use in static context below
    public static final String SERVER_AUTH_CONTEXT_KEY = "serverAuthContext";
    public static final String SESSION_MODULE_KEY = "sessionModule";
    public static final String AUTH_MODULES_KEY = "authModules";
    public static final String AUTH_MODULE_PROPERTIES_KEY = "properties";
    public static final String AUTH_MODULE_CLASS_NAME_KEY = "className";
    public static final String MODULE_CONFIG_ENABLED = "enabled";

    private JsonValue config;


    /** The authenticators to delegate to.*/
    private List<Authenticator> authenticators = new ArrayList<>();

    // ----- Declarative Service Implementation

    @Reference(policy = ReferencePolicy.DYNAMIC)
    CryptoService cryptoService;

    /** The Connection Factory */
    @Reference(policy = ReferencePolicy.STATIC, target="(service.pid=org.forgerock.openidm.internal)")
    protected ConnectionFactory connectionFactory;

    /** Enhanced configuration service. */
    @Reference(policy = ReferencePolicy.DYNAMIC)
    private EnhancedConfig enhancedConfig;

    /** a factory Function to build an Authenticator from an auth module config */
    private AuthenticatorFactory toAuthenticatorFromProperties;


    /** A {@link Predicate} that returns whether the auth module is enabled */
    private static final Predicate<JsonValue> enabledAuthModules =
            new Predicate<JsonValue>() {
                @Override
                public boolean apply(JsonValue jsonValue) {
                    return jsonValue.get(MODULE_CONFIG_ENABLED).defaultTo(true).asBoolean();
                }
            };

    /** A {@link Function} that returns the auth modules properties as a JsonValue */
    private static final Function<JsonValue, JsonValue> toModuleProperties =
            new Function<JsonValue, JsonValue>() {
                @Override
                public JsonValue apply(JsonValue value) {
                    return value.get(AUTH_MODULE_PROPERTIES_KEY);
                }
            };

    /** A {link Predicate} that validates an auth module's properties for the purposes of instantiating an Authenticator */
    private static final Predicate<JsonValue> authModulesThatHaveValidAuthenticatorProperties =
            new Predicate<JsonValue>() {
                @Override
                public boolean apply(JsonValue jsonValue) {
                    // must have "queryOnResource"
                    return jsonValue.get(QUERY_ON_RESOURCE).isString()
                        // must either not have "queryId"
                        && (jsonValue.get(QUERY_ID).isNull()
                                // or have "queryId" + "authenticationId"/"userCredential" property mapping
                                || (jsonValue.get(QUERY_ID).isString()
                                    && jsonValue.get(PROPERTY_MAPPING).get(AUTHENTICATION_ID).isString()
                                    && jsonValue.get(PROPERTY_MAPPING).get(USER_CREDENTIAL).isString()));
                }
            };

    /**
     * Activates this component.
     *
     * @param context The ComponentContext
     */
    @Activate
    public synchronized void activate(ComponentContext context) {
        logger.info("Activating Authentication Service with configuration {}", context.getProperties());
        config = enhancedConfig.getConfigurationAsJson(context);

        // factory Function that instantiates an Authenticator from an auth module's config properties
        toAuthenticatorFromProperties = new AuthenticatorFactory(connectionFactory, cryptoService);

        // the auth module list config lives under at /serverAuthConfig/authModule
        final JsonValue authModuleConfig = config.get(SERVER_AUTH_CONTEXT_KEY).get(AUTH_MODULES_KEY);

        // filter enabled module configs and get their properties;
        // then filter those with valid auth properties, and build an authenticator
        for (final Authenticator authenticator :
                FluentIterable.from(authModuleConfig)
                .filter(enabledAuthModules)
                .transform(toModuleProperties)
                .filter(authModulesThatHaveValidAuthenticatorProperties)
                .transform(toAuthenticatorFromProperties)) {
            authenticators.add(authenticator);
        }

        logger.debug("OpenIDM Config for Authentication {} is activated.", config.get(Constants.SERVICE_PID));
    }

    /**
     * Nulls the stored authentication JsonValue.
     *
     * @param context The ComponentContext.
     */
    @Deactivate
    public void deactivate(ComponentContext context) {
        logger.debug("OpenIDM Config for Authentication {} is deactivated.", config.get(Constants.SERVICE_PID));
        config = null;
        toAuthenticatorFromProperties = null;
        authenticators.clear();
    }

    // ----- Implementation of AuthenticationConfig interface

    /**
     * {@inheritDoc}
     */
    @Override
    public JsonValue getConfig() {
        return config;
    }

    // ----- Implementation of SingletonResourceProvider interface

    private enum Action { reauthenticate };

    /**
     * Action support, including reauthenticate action {@inheritDoc}
     */
    @Override
    public Promise<ActionResponse, ResourceException> actionInstance(Context context, ActionRequest request) {
        try {
            if (Action.reauthenticate.equals(request.getActionAsEnum(Action.class))) {
                if (context.containsContext(HttpContext.class)
                        && context.containsContext(SecurityContext.class)) {
                    String authcid = context.asContext(SecurityContext.class).getAuthenticationId();
                    HttpContext httpContext = context.asContext(HttpContext.class);
                    String password = httpContext.getHeaderAsString(HEADER_REAUTH_PASSWORD);
                    if (StringUtils.isBlank(authcid) || StringUtils.isBlank(password)) {
                        logger.debug("Reauthentication failed, missing or empty headers");
                        return new ForbiddenException("Reauthentication failed, missing or empty headers")
                                .asPromise();
                    }

                    for (Authenticator authenticator : authenticators) {
                        try {
                            if (authenticator.authenticate(authcid, password, context).isAuthenticated()) {
                                JsonValue result = new JsonValue(new HashMap<String, Object>());
                                result.put("reauthenticated", true);
                                return newActionResponse(result).asPromise();
                            }
                        } catch (ResourceException e) {
                            // log error and try next authentication mechanism
                            logger.debug("Reauthentication failed: {}", e.getMessage());
                        }
                    }

                    return new ForbiddenException("Reauthentication failed for " + authcid).asPromise();
                } else {
                    return new InternalServerErrorException("Failure to reauthenticate - missing context").asPromise();
                }
            } else {
                return new BadRequestException("Action " + request.getAction() + " on authentication service not supported")
                        .asPromise();
            }
        } catch (IllegalArgumentException e) { // from getActionAsEnum
            return new BadRequestException("Action " + request.getAction() + " on authentication service not supported")
                    .asPromise();
        } catch (Exception e) {
            return new InternalServerErrorException("Error processing action", e).asPromise();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> patchInstance(Context context, PatchRequest request) {
        return new NotSupportedException("Patch operation not supported").asPromise();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> readInstance(Context context, ReadRequest request) {
        return new NotSupportedException("Read operation not supported").asPromise();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> updateInstance(Context context, UpdateRequest request) {
        return new NotSupportedException("Update operation not supported").asPromise();
    }
}
