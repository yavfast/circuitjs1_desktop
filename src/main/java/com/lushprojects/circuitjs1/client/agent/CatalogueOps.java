package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.List;

/**
 * [SP_AGA_02_01] listTypes / describeType: read the session element catalogue
 * ({@link Catalogue}, built on the first call). Session-scoped, served while a document is busy.
 */
final class CatalogueOps {

    private CatalogueOps() {
    }

    static void register(AgentApi api) {
        api.register("listTypes", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, CatalogueOps::listTypes);
        api.register("describeType", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, CatalogueOps::describeType);
    }

    /** {@code listTypes(filter?)} → {@code data.types}: index entries sorted by type. */
    static OperationResult listTypes(AgentApi.Call call) {
        String filter = call.args.optString("filter", null);
        if (call.args.failed()) {
            return call.args.failure();
        }
        List<Catalogue.TypeInfo> types = call.sim.getAgentCatalogue().list(filter);
        JSONArray list = new JSONArray();
        for (int i = 0; i < types.size(); i++) {
            list.set(i, types.get(i).toIndexJson());
        }
        JSONObject data = new JSONObject();
        data.put("types", list);
        return OperationResult.success(data);
    }

    /** {@code describeType(type)} → TypeInfo; a name that is no type or alias is {@code unknown_type}. */
    static OperationResult describeType(AgentApi.Call call) {
        String type = call.args.requireString("type");
        if (call.args.failed()) {
            return call.args.failure();
        }
        Catalogue cat = call.sim.getAgentCatalogue();
        Catalogue.TypeInfo info = cat.find(type);
        if (info == null) {
            return OperationResult.failure(Issue.of(IssueCode.UNKNOWN_TYPE,
                    "Argument 'type' names no catalogue type or alias: '" + EchoText.clip(type) + "'.",
                    "Closest names: " + String.join(", ", cat.closestNames(type, Catalogue.HINT_NAMES))
                            + ". listTypes shows every type."));
        }
        return OperationResult.success(info.toJson());
    }
}
