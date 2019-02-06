/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action.admin.indices;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RestCreateIndexAction extends BaseRestHandler {
    private static final DeprecationLogger deprecationLogger = new DeprecationLogger(
        LogManager.getLogger(RestPutMappingAction.class));
    public static final String TYPES_DEPRECATION_MESSAGE = "[types removal] Specifying types in create index " +
        "requests is deprecated. To be compatible with 7.0, the mapping definition should not be nested under " +
        "the type name, and the parameter include_type_name must be provided and set to false.";

    public RestCreateIndexAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(RestRequest.Method.PUT, "/{index}", this);
    }

    @Override
    public String getName() {
        return "create_index_action";
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(request.param("index"));

        boolean includeTypeName = request.paramAsBoolean(INCLUDE_TYPE_NAME_PARAMETER,
            DEFAULT_INCLUDE_TYPE_NAME_POLICY);
        if (request.hasContent()) {
            Map<String, Object> sourceAsMap = XContentHelper.convertToMap(request.content(), false, request.getXContentType()).v2();
            if (includeTypeName && sourceAsMap.containsKey("mappings")) {
                deprecationLogger.deprecatedAndMaybeLog("create_index_with_types", TYPES_DEPRECATION_MESSAGE);
            }
            sourceAsMap = prepareMappings(sourceAsMap, includeTypeName);
            createIndexRequest.source(sourceAsMap, LoggingDeprecationHandler.INSTANCE);
        }

        if (request.hasParam("update_all_types")) {
            deprecationLogger.deprecated("[update_all_types] is deprecated since indices may not have more than one type anymore");
        }
        createIndexRequest.updateAllTypes(request.paramAsBoolean("update_all_types", false));
        createIndexRequest.timeout(request.paramAsTime("timeout", createIndexRequest.timeout()));
        createIndexRequest.masterNodeTimeout(request.paramAsTime("master_timeout", createIndexRequest.masterNodeTimeout()));
        createIndexRequest.waitForActiveShards(ActiveShardCount.parseString(request.param("wait_for_active_shards")));
        return channel -> client.admin().indices().create(createIndexRequest, new RestToXContentListener<>(channel));
    }

    static Map<String, Object> prepareMappings(Map<String, Object> source, boolean includeTypeName) {
        if (includeTypeName
            || source.containsKey("mappings") == false
            || (source.get("mappings") instanceof Map) == false) {
            return source;
        }

        Map<String, Object> newSource = new HashMap<>(source);

        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) source.get("mappings");

        if (MapperService.isMappingSourceTyped(MapperService.SINGLE_MAPPING_NAME, mappings)) {
            throw new IllegalArgumentException("The mapping definition cannot be nested under a type " +
                "[" + MapperService.SINGLE_MAPPING_NAME + "] unless include_type_name is set to true.");
        }

        newSource.put("mappings", Collections.singletonMap(MapperService.SINGLE_MAPPING_NAME, mappings));
        return newSource;
    }
}
