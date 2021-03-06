/*
 * Copyright (c) 2017 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.message.config;

import com.couchbase.client.core.message.AbstractCouchbaseRequest;

/**
 * @author Subhashni Balakrishnan
 * @since 1.4.4
 */
public class RemoveUserRequest extends AbstractCouchbaseRequest implements ConfigRequest {

    private final String userId;
    private final String domain;

    public RemoveUserRequest(String username, String password, String domain, String userId) {
        super(username, password);
        this.userId = userId;
        this.domain = domain;
    }

    @Override
    public String path() {
        return "/settings/rbac/users/" + domain + "/" + userId;
    }
}