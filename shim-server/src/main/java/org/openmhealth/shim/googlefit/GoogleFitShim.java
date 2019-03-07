/*
 * Copyright 2017 Open mHealth
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.openmhealth.shim.googlefit;

import com.fasterxml.jackson.databind.JsonNode;
import org.openmhealth.shim.*;
import org.openmhealth.shim.googlefit.mapper.*;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.security.oauth2.client.resource.OAuth2AccessDeniedException;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.resource.UserRedirectRequiredException;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.RequestEnhancer;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeAccessTokenProvider;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.http.ResponseEntity.ok;


/**
 * Encapsulates parameters specific to the Google Fit REST API and processes requests for Google Fit data from shimmer.
 *
 * @author Eric Jain
 * @author Chris Schaefbauer
 */
@Component
public class GoogleFitShim extends OAuth2Shim {

    private static final Logger logger = getLogger(GoogleFitShim.class);

    public static final String SHIM_KEY = "googlefit";
    private static final String DATA_URL = "https://www.googleapis.com/fitness/v1/users/me/dataSources";
    private static final String USER_AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/auth";
    private static final String ACCESS_TOKEN_URL = "https://accounts.google.com/o/oauth2/token";

    @Autowired
    private GoogleFitClientSettings clientSettings;

    @Override
    public String getLabel() {

        return "Google Fit";
    }

    @Override
    public String getShimKey() {

        return SHIM_KEY;
    }

    @Override
    public String getUserAuthorizationUrl() {

        return USER_AUTHORIZATION_URL;
    }

    @Override
    public String getAccessTokenUrl() {

        return ACCESS_TOKEN_URL;
    }

    @Override
    protected OAuth2ClientSettings getClientSettings() {

        return clientSettings;
    }

    public AuthorizationCodeAccessTokenProvider getAuthorizationCodeAccessTokenProvider() {

        return new GoogleAuthorizationCodeAccessTokenProvider();
    }

    @Override
    public ShimDataType[] getShimDataTypes() {

        return new GoogleFitDataTypes[] {
                GoogleFitDataTypes.BODY_HEIGHT,
                GoogleFitDataTypes.BODY_WEIGHT,
                GoogleFitDataTypes.CALORIES_BURNED,
                GoogleFitDataTypes.GEOPOSITION,
                GoogleFitDataTypes.HEART_RATE,
                GoogleFitDataTypes.PHYSICAL_ACTIVITY,
                GoogleFitDataTypes.SPEED,
                GoogleFitDataTypes.STEP_COUNT
        };
    }

    public enum GoogleFitDataTypes implements ShimDataType {

        BODY_HEIGHT("derived:com.google.height:com.google.android.gms:merge_height"),
        BODY_WEIGHT("derived:com.google.weight:com.google.android.gms:merge_weight"),
        CALORIES_BURNED("derived:com.google.calories.expended:com.google.android.gms:merge_calories_expended"),
        GEOPOSITION("derived:com.google.location.sample:com.google.android.gms:merge_location_samples"),
        HEART_RATE("derived:com.google.heart_rate.bpm:com.google.android.gms:merge_heart_rate_bpm"),
        PHYSICAL_ACTIVITY("derived:com.google.activity.segment:com.google.android.gms:merge_activity_segments"),
        SPEED("derived:com.google.speed:com.google.android.gms:merge_speed"),
        STEP_COUNT("derived:com.google.step_count.delta:com.google.android.gms:merge_step_deltas");

        private final String streamId;

        GoogleFitDataTypes(String streamId) {
            this.streamId = streamId;
        }

        public String getStreamId() {
            return streamId;
        }
    }

    protected ResponseEntity<ShimDataResponse> getData(OAuth2RestOperations restTemplate,
            ShimDataRequest shimDataRequest) throws ShimException {

        final GoogleFitDataTypes googleFitDataType;
        try {
            googleFitDataType = GoogleFitDataTypes.valueOf(
                    shimDataRequest.getDataTypeKey().trim().toUpperCase());
        }
        catch (NullPointerException | IllegalArgumentException e) {
            throw new ShimException("Null or Invalid data type parameter: "
                    + shimDataRequest.getDataTypeKey()
                    + " in shimDataRequest, cannot retrieve data.");
        }

        OffsetDateTime todayInUTC =
                LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC);

        OffsetDateTime startDateInUTC = shimDataRequest.getStartDateTime() == null ?

                todayInUTC.minusDays(1) : shimDataRequest.getStartDateTime();
        long startTimeNanos = (startDateInUTC.toEpochSecond() * 1_000_000_000) + startDateInUTC.toInstant().getNano();

        OffsetDateTime endDateInUTC = shimDataRequest.getEndDateTime() == null ?
                todayInUTC.plusDays(1) :
                shimDataRequest.getEndDateTime().plusDays(1);   // We are inclusive of the last day, so add 1 day to get

        // the end of day on the last day, which captures the
        // entire last day
        long endTimeNanos = (endDateInUTC.toEpochSecond() * 1_000_000_000) + endDateInUTC.toInstant().getNano();


        // TODO: Add limits back into the request once Google has fixed the 'limit' query parameter and paging
        URI uri = UriComponentsBuilder
                .fromUriString(DATA_URL)
                .pathSegment(googleFitDataType.getStreamId(), "datasets", "{startDate}-{endDate}")
                .buildAndExpand(startTimeNanos, endTimeNanos)
                .encode()
                .toUri();

        ResponseEntity<JsonNode> responseEntity;
        try {
            responseEntity = restTemplate.getForEntity(uri, JsonNode.class);
        }
        catch (HttpClientErrorException | HttpServerErrorException e) {
            // TODO figure out how to handle this
            logger.error("A request for Google Fit data failed.", e);
            throw e;
        }

        if (shimDataRequest.getNormalize()) {
            GoogleFitDataPointMapper<?> dataPointMapper = getDataPointMapper(googleFitDataType);

            return ok().body(ShimDataResponse
                    .result(GoogleFitShim.SHIM_KEY, dataPointMapper.asDataPoints(responseEntity.getBody())));
        }
        else {
            return ok().body(ShimDataResponse
                    .result(GoogleFitShim.SHIM_KEY, responseEntity.getBody()));
        }
    }

    private GoogleFitDataPointMapper<?> getDataPointMapper(GoogleFitDataTypes googleFitDataType) {

        switch (googleFitDataType) {
            case BODY_HEIGHT:
                return new GoogleFitBodyHeightDataPointMapper();

            case BODY_WEIGHT:
                return new GoogleFitBodyWeightDataPointMapper();

            case CALORIES_BURNED:
                return new GoogleFitCaloriesBurnedDataPointMapper();

            case GEOPOSITION:
                return new GoogleFitGeopositionDataPointMapper();

            case HEART_RATE:
                return new GoogleFitHeartRateDataPointMapper();

            case PHYSICAL_ACTIVITY:
                return new GoogleFitPhysicalActivityDataPointMapper();

            case SPEED:
                return new GoogleFitSpeedDataPointMapper();

            case STEP_COUNT:
                return new GoogleFitStepCountDataPointMapper();

            default:
                throw new UnsupportedOperationException();
        }
    }

    @Override
    protected String getAuthorizationUrl(UserRedirectRequiredException exception, Map<String, String> addlParameters) {

        final OAuth2ProtectedResourceDetails resource = getResource();

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(exception.getRedirectUri())
                .queryParam("state", exception.getStateKey())
                .queryParam("client_id", resource.getClientId())
                .queryParam("response_type", "code")
                .queryParam("access_type", "offline")
                .queryParam("approval_prompt", "force")
                .queryParam("scope", StringUtils.collectionToDelimitedString(resource.getScope(), " "))
                .queryParam("redirect_uri", getDefaultRedirectUrl());

        return uriBuilder.build().encode().toUriString();
    }

    /**
     * Simple overrides to base spring class from oauth.
     */
    public class GoogleAuthorizationCodeAccessTokenProvider extends AuthorizationCodeAccessTokenProvider {

        public GoogleAuthorizationCodeAccessTokenProvider() {

            this.setTokenRequestEnhancer(new GoogleTokenRequestEnhancer());
        }

        @Override
        protected HttpMethod getHttpMethod() {

            return HttpMethod.POST;
        }

        @Override
        public OAuth2AccessToken refreshAccessToken(
                OAuth2ProtectedResourceDetails resource,
                OAuth2RefreshToken refreshToken, AccessTokenRequest request)
                throws UserRedirectRequiredException,
                OAuth2AccessDeniedException {

            OAuth2AccessToken accessToken = super.refreshAccessToken(resource, refreshToken, request);
            // Google does not replace refresh tokens, so we need to hold on to the existing refresh token...
            if (accessToken.getRefreshToken() == null) {
                ((DefaultOAuth2AccessToken) accessToken).setRefreshToken(refreshToken);
            }
            return accessToken;
        }
    }


    /**
     * Adds parameters required by Google to authorization token requests.
     */
    private class GoogleTokenRequestEnhancer implements RequestEnhancer {

        @Override
        public void enhance(AccessTokenRequest request,
                OAuth2ProtectedResourceDetails resource,
                MultiValueMap<String, String> form, HttpHeaders headers) {

            form.set("client_id", resource.getClientId());
            form.set("client_secret", resource.getClientSecret());
            if (request.getStateKey() != null) {
                form.set("redirect_uri", getDefaultRedirectUrl());
            }
        }
    }
}
