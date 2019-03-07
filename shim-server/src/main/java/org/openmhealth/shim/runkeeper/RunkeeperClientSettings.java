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

package org.openmhealth.shim.runkeeper;

import org.openmhealth.shim.OAuth2ClientSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;


/**
 * @author Emerson Farrugia
 */
@Component
@ConfigurationProperties("openmhealth.shim.runkeeper")
public class RunkeeperClientSettings extends OAuth2ClientSettings {

    private List<String> scopes = Arrays.asList(
            "application/vnd.com.runkeeper.FitnessActivityFeed+json"
    );

    @Override
    public List<String> getScopes() {

        return scopes;
    }

    public void setScopes(List<String> scopes) {

        this.scopes = scopes;
    }
}
