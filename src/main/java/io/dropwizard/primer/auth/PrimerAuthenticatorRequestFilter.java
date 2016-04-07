/*
 * Copyright 2016 Phaneesh Nagaraja <phaneesh.n@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dropwizard.primer.auth;

import com.codahale.metrics.annotation.Metered;
import com.github.toastshaman.dropwizard.auth.jwt.JsonWebTokenParser;
import com.github.toastshaman.dropwizard.auth.jwt.exceptions.TokenExpiredException;
import com.github.toastshaman.dropwizard.auth.jwt.hmac.HmacSHA512Verifier;
import com.github.toastshaman.dropwizard.auth.jwt.model.JsonWebToken;
import com.google.common.base.Optional;
import feign.FeignException;
import io.dropwizard.primer.cache.TokenCacheManager;
import io.dropwizard.primer.client.PrimerClient;
import io.dropwizard.primer.core.PrimerError;
import io.dropwizard.primer.core.ServiceUser;
import io.dropwizard.primer.core.VerifyResponse;
import io.dropwizard.primer.exception.PrimerException;
import io.dropwizard.primer.model.PrimerBundleConfiguration;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Interval;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Optional.fromNullable;


/**
 * @author phaneesh
 */
@Slf4j
@Provider
@Priority(Priorities.AUTHENTICATION)
public class PrimerAuthenticatorRequestFilter implements ContainerRequestFilter {


    private JsonWebTokenParser tokenParser;

    private HmacSHA512Verifier verifier;

    private PrimerBundleConfiguration configuration;

    private final Duration acceptableClockSkew;

    private final PrimerClient primerClient;

    @Builder
    public PrimerAuthenticatorRequestFilter(final JsonWebTokenParser tokenParser,
                                            final HmacSHA512Verifier verifier,
                                            final PrimerBundleConfiguration configuration, final PrimerClient primerClient) {
        this.tokenParser = tokenParser;
        this.verifier = verifier;
        this.configuration = configuration;
        this.acceptableClockSkew = new Duration(configuration.getClockSkew());
        this.primerClient = primerClient;
    }

    @Override
    @Metered
    public void filter(ContainerRequestContext requestContext) throws IOException {
        //Short circuit for all white listed urls
        if(isWhilisted(requestContext.getUriInfo().getPath())) {
            return;
        }
        Optional<String> token = getToken(requestContext);
        if(!token.isPresent()) {
            requestContext.abortWith(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(PrimerError.builder().errorCode("PR000").message("Bad request")
                    .build()).build()
            );
        } else {
            try {
                if(TokenCacheManager.checkBlackList(token.get())) {
                    requestContext.abortWith(
                            Response.status(Response.Status.FORBIDDEN)
                                    .entity(PrimerError.builder().errorCode("PR004").message("Forbidden")
                                            .build()).build()
                    );
                }
                if(TokenCacheManager.checkCache(token.get())) {
                    //Short circuit for optimization
                    return;
                }
            } catch (ExecutionException e) {
                //Ignore execution execution because of rejection
                log.warn("Error getting token from cache: {}", e.getMessage());
            }
            JsonWebToken webToken = verifyToken(token.get());
            try {

                final VerifyResponse verifyResponse = primerClient.verify(
                        webToken.claim().issuer(),
                        webToken.claim().subject(),
                        token.get(),
                        ServiceUser.builder()
                                .id(webToken.claim().subject())
                                .name((String)webToken.claim().getParameter("name"))
                                .role((String)webToken.claim().getParameter("role"))
                        .build()
                );
                if(!StringUtils.isBlank(verifyResponse.getToken()) && !StringUtils.isBlank(verifyResponse.getUserId())) {
                    TokenCacheManager.cache(token.get());
                }
            } catch (TokenExpiredException e) {
                log.error("Token Expiry Error", e);
                requestContext.abortWith(
                        Response.status(Response.Status.PRECONDITION_FAILED)
                                .entity(PrimerError.builder().errorCode("PR003").message("Expired")
                                        .build()).build()
                );
            } catch (FeignException e) {
                log.error("Feign error", e);
                if(e.status() == 403) {
                    TokenCacheManager.blackList(token.get());
                }
                requestContext.abortWith(
                        Response.status(e.status())
                                .entity(PrimerError.builder().errorCode("PR000").message("Error")
                                        .build()).build()
                );
            } catch (PrimerException e) {
                log.error("Primer error", e);
                if(e.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
                    TokenCacheManager.blackList(token.get());
                }
                requestContext.abortWith(
                        Response.status(e.getStatus())
                                .entity(PrimerError.builder().errorCode(e.getErrorCode()).message(e.getMessage()).build())
                                        .build());
            }

        }
    }

    private Optional<String> getToken(ContainerRequestContext requestContext) {
        final String header = requestContext.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        log.info("Authorization Header: {}", header);
        if (header != null) {
            return Optional.of(header.replaceAll(configuration.getPrefix(), "").trim());
        }
        return Optional.absent();
    }

    private JsonWebToken verifyToken(String rawToken) {
        final JsonWebToken token = tokenParser.parse(rawToken);
        verifier.verifySignature(token);
        return token;
    }

    private void checkExpiry(JsonWebToken token) {
        if (token.claim() != null) {
            final Instant now = new Instant();
            final Instant issuedAt = fromNullable(toInstant(token.claim().issuedAt())).or(now);
            final Instant expiration = fromNullable(toInstant(token.claim().expiration())).or(new Instant(Long.MAX_VALUE));
            final Instant notBefore = fromNullable(toInstant(token.claim().notBefore())).or(now);

            if (issuedAt.isAfter(expiration) || notBefore.isAfterNow() || !inInterval(issuedAt, expiration, now)) {
                throw new TokenExpiredException();
            }
        }
    }

    private boolean inInterval(Instant start, Instant end, Instant now) {
        final Interval interval = new Interval(start, end);
        final Interval currentTimeWithSkew = new Interval(now.minus(acceptableClockSkew), now.plus(acceptableClockSkew));
        return interval.overlaps(currentTimeWithSkew);
    }

    private Instant toInstant(Long input) {
        if (input == null) {
            return null;
        }
        return new Instant(input * 1000);
    }

    private boolean isWhilisted(final String path) {
        return configuration.getWhileListUrl().parallelStream().filter(path::startsWith).count() > 0;
    }
}
