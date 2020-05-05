/*
 * SnapLogic - Data Integration
 *
 * Copyright (C) 2016, SnapLogic, Inc.  All rights reserved.
 *
 * This program is licensed under the terms of
 * the SnapLogic Commercial Subscription agreement.
 *
 * "SnapLogic" is a trademark of SnapLogic, Inc.
 * 
 * @author lhakansson@snaplogic.com
 */

package com.snaplogic.snaps;

import java.util.Map;

import com.snaplogic.account.api.capabilities.Accounts;
import com.snaplogic.api.ConfigurationException;
import com.snaplogic.api.InputSchemaProvider;
import com.snaplogic.common.SnapType;
import com.snaplogic.common.properties.SnapProperty;
import com.snaplogic.common.properties.Suggestions;
import com.snaplogic.common.properties.builders.PropertyBuilder;
import com.snaplogic.common.properties.builders.SuggestionBuilder;
import com.snaplogic.snap.api.Document;
import com.snaplogic.snap.schema.api.SchemaBuilder;
import com.snaplogic.snap.api.PropertyCategory;
import com.snaplogic.snap.api.PropertyValues;
import com.snaplogic.snap.api.SimpleSnap;
import com.snaplogic.snap.api.SnapCategory;
import com.snaplogic.snap.api.capabilities.Category;
import com.snaplogic.snap.api.capabilities.Errors;
import com.snaplogic.snap.api.capabilities.General;
import com.snaplogic.snap.api.capabilities.Inputs;
import com.snaplogic.snap.api.capabilities.Outputs;
import com.snaplogic.snap.api.capabilities.Version;
import org.openapi4j.parser.model.v3.Parameter;
import com.snaplogic.snap.api.capabilities.ViewType;
import com.snaplogic.snap.schema.api.ObjectSchema;
import com.snaplogic.snap.schema.api.Schema;
import com.snaplogic.snap.schema.api.SchemaProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import org.openapi4j.parser.model.v3.MediaType;

import org.openapi4j.parser.model.v3.Path;
import org.openapi4j.parser.model.v3.OpenApi3;
import org.openapi4j.parser.model.v3.Operation;
import org.openapi4j.parser.OpenApi3Parser;

@General(title = "Marketo Read", purpose = "Read Marketo asset information",
        author = "SnapLogic", docLink = "http://yourdocslinkhere.com")
@Inputs(min = 1, max = 1, accepts = {ViewType.DOCUMENT})
@Outputs(min = 1, max = 1, offers = {ViewType.DOCUMENT})
@Errors(min = 1, max = 1, offers = {ViewType.DOCUMENT})
@Version(snap = 1)
@Category(snap = SnapCategory.READ)
@Accounts(provides = {MarketoAccount.class}, optional = false)

public class MarketoRead extends SimpleSnap implements InputSchemaProvider {
    private static final String INPUT_VIEW_NAME = "input0";

    public static final String TAG_NAME = "Object";
    public static final String TAG_LABEL = "Object";
    public static final String TAG_DESC = "The object";

    public static final String OPERATION_NAME = "Operation";
    public static final String OPERATION_LABEL = "Operation";
    public static final String OPERATION_DESC = "The operation";

    public static final String PARAMETER_NAME = "Parameters";
    public static final String PARAMETER_LABEL = "Parameters";
    public static final String PARAMETER_DESC = "The parameters";   

    public static final String COLUMN_PARAMETER_KEY = "Path parameter";
    public static final String COLUMN_PARAMETER_VALUE = "Path value";
    public static final String TABLE_PARAMETERS = "Path parameters";

    private enum ParameterIn {
        PATH,
        QUERY,
        HEADER
      }

    private String propServerUrl;
    private String propPath;
    private String propOperation;
    private List<Map<String, Object>> pathPararmeters;
    private String munchkinId;
    private OpenApi3 api;

    private final String MARKETO_SWAGGER_URL = "https://developers.marketo.com/swagger/host_fixer_mapi.php?munchkin=";

    @Inject
    private MarketoAccount marketoAccount;

    @Override
    public void defineInputSchema(final SchemaProvider provider) {
        SchemaBuilder builder = provider.getSchemaBuilder(INPUT_VIEW_NAME);

        try {
            Operation op = null;
            for (Map.Entry<String, Path> pathEntry : getAPI().getPaths().entrySet()) {
                for (Map.Entry<String, Operation> entry : pathEntry.getValue().getOperations().entrySet()) {
                    if (entry.getValue().getSummary().equals(propOperation)) {
                        op = entry.getValue();
                        break;
                    }
                }
            }

            List<Parameter> parameters = op.getParameters();

            if (parameters != null) {
                ObjectSchema params = provider.createSchema(SnapType.COMPOSITE, "parameters");
                ObjectSchema queryParams = provider.createSchema(SnapType.COMPOSITE, "query");
                ObjectSchema headerParams = provider.createSchema(SnapType.COMPOSITE, "header");
                for (Parameter parameter : parameters) {
                    Schema schema = provider.createSchema(SnapType.STRING, parameter.getName());
                    // TODO: Required flag doesn't seem to work. Check if the value from the parameter is correct or if 
                    // error is anywhere else
                    schema.setRequired(parameter.isRequired());
                    switch (ParameterIn.valueOf(parameter.getIn().toUpperCase())) {
                        case QUERY:
                            queryParams.addChild(schema);
                            break;
                        default:
                            headerParams.addChild(schema);
                            break;
                    }

                }
                if (queryParams.getSize() > 0) {
                    params.addChild(queryParams);
                }
                if (headerParams.getSize() > 0) {
                    params.addChild(headerParams);
                }
                builder.withChildSchema(params);
            }

            // has not checked if this code is working yet
            if (op.getRequestBody() != null && op.getRequestBody().getContentMediaTypes() != null) {
                ObjectSchema body = provider.createSchema(SnapType.COMPOSITE, "body");
                Map<String, MediaType> mediatypes = op.getRequestBody().getContentMediaTypes();
                JsonNode bodySchema = null;

                for (Map.Entry<String, MediaType> entry : mediatypes.entrySet()) {
                    if (entry.getKey().equals("application/json")) {
                        bodySchema = entry.getValue().getSchema().getReference(api.getContext()).getContent();
                        break;
                    }
                }

                Iterator<Entry<String, JsonNode>> fields = bodySchema.fields();
                ArrayList<String> requiredFields = new ArrayList<String>();

                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> pair = fields.next();

                    if (pair.getKey().equals("required")) {
                        for (final JsonNode field : pair.getValue()) {
                            requiredFields.add(field.asText());
                        }    
                    }

                    if (pair.getKey().equals("properties")) {
                        Iterator<Entry<String, JsonNode>> properties = pair.getValue().fields();
                        while (properties.hasNext()) {
                            Map.Entry<String, JsonNode> property = properties.next();
                            Schema schema = provider.createSchema(SnapType.STRING, property.getKey());
                            schema.setRequired(requiredFields.contains(property.getKey()));
                            body.addChild(schema);
                        }
                        
                    }
                }
                builder.withChildSchema(body);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        builder.build();
    }

    @Override
    public void defineProperties(final PropertyBuilder propertyBuilder) {
        munchkinId = marketoAccount.connect();

        propertyBuilder.describe(TAG_NAME, TAG_LABEL, TAG_DESC)
                .withSuggestions(new Suggestions() {
                    @Override
                    public void suggest(SuggestionBuilder suggestionBuilder,
                            PropertyValues propertyValues) {
                                ArrayList<String> tags = new ArrayList<String>();
                                try {
                                    for (Map.Entry<String, Path> pathEntry : getAPI().getPaths().entrySet()) {
                                        for (Map.Entry<String, Operation> entry : pathEntry.getValue().getOperations().entrySet()) {
                                            if (entry.getKey().equals("get")) {
                                                if (!tags.contains(entry.getValue().getTags().get(0))){
                                                    tags.add(entry.getValue().getTags().get(0));
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                }
                            suggestionBuilder.node(TAG_NAME).suggestions(tags.toArray(new String[0]));
                    }
                }).add();

        propertyBuilder.describe(OPERATION_NAME, OPERATION_LABEL, OPERATION_DESC)
                .withSuggestions(new Suggestions() {
                    @Override
                    public void suggest(SuggestionBuilder suggestionBuilder,
                            PropertyValues propertyValues) {
                                String objectName = propertyValues.get(PropertyCategory.SETTINGS, TAG_NAME);
                                if (objectName == null) {
                                    suggestionBuilder.node(OPERATION_NAME).suggestions("No operation found for object");
                                    return;
                                }
                                ArrayList<String> operations = new ArrayList<String>();
                                try {
                                    for (Map.Entry<String, Path> pathEntry : getAPI().getPaths().entrySet()) {
                                        for (Map.Entry<String, Operation> entry : pathEntry.getValue().getOperations().entrySet()) {
                                            if (entry.getKey().equals("get")) {
                                                if (entry.getValue().getTags().get(0).equals(objectName)) {
                                                    operations.add(entry.getValue().getSummary());
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                }
                            suggestionBuilder.node(OPERATION_NAME).suggestions(operations.toArray(new String[0]));
                    }
                }).add();

        SnapProperty parametersKeyColumn = propertyBuilder.describe(COLUMN_PARAMETER_KEY,
                COLUMN_PARAMETER_KEY)
                .withSuggestions(new Suggestions() {
                    @Override
                    public void suggest(SuggestionBuilder suggestionBuilder,
                            PropertyValues propertyValues) {
                                String objectName = propertyValues.get(PropertyCategory.SETTINGS, OPERATION_NAME);
                                suggestionBuilder.node(TABLE_PARAMETERS).over(COLUMN_PARAMETER_KEY).suggestions(getParameterSuggestions(objectName));
                    }
                })
                .build();
        SnapProperty parametersValueColumn = propertyBuilder.describe(COLUMN_PARAMETER_VALUE, COLUMN_PARAMETER_VALUE)
                .required()
                .expression()
                .build();
                propertyBuilder.describe(TABLE_PARAMETERS, TABLE_PARAMETERS)
                .type(SnapType.TABLE)
                .withEntry(parametersKeyColumn)
                .withEntry(parametersValueColumn)
                .add();
     
    }

    @Override
    public void configure(PropertyValues propertyValues)
            throws ConfigurationException {

        try {
            propServerUrl = "https://" + getAPI().getContext().getBaseDocument().get("host").toString().replace("\"", "");
        } catch (Exception e) {
        }

        propPath = propertyValues.getAsExpression(TAG_NAME).eval(null);
        propOperation = propertyValues.getAsExpression(OPERATION_NAME).eval(null);
        pathPararmeters = buildParametersTable(propertyValues);
    }


    protected List<Map<String, Object>> buildParametersTable(final PropertyValues propertyValues) {
        List<Map<String, Object>> table = new ArrayList<>();

        List<Map<String, Object>> tableProp = propertyValues.getAsExpression(TABLE_PARAMETERS).eval(null);

        for (Map<String, Object> aRow : tableProp) {
            Map<String, Object> row = new LinkedHashMap<>();

            String childFileBrowser =
                    propertyValues.getExpressionPropertyFor(aRow, COLUMN_PARAMETER_KEY)
                            .eval(null);
            String column = propertyValues.getExpressionPropertyFor(aRow, COLUMN_PARAMETER_VALUE).eval(null);

            row.put(COLUMN_PARAMETER_KEY, childFileBrowser);
            row.put(COLUMN_PARAMETER_VALUE, column);

            table.add(row);
        }

        return table;
    }

    @Override
    protected void process(Document document, final String inputViewName) {
        String path = null;
        
        // find the right Path object based on the operation the user selected 
        for (Map.Entry<String, Path> pathEntry : getAPI().getPaths().entrySet()) {
            for (Map.Entry<String, Operation> entry : pathEntry.getValue().getOperations().entrySet()) {
                if (entry.getValue().getSummary().equals(propOperation)) {
                    path = pathEntry.getKey();
                    break;
                }
            }
        }

        // the all the path parameters that were added by the user and replace the placeholders in the URL
        for (Map<String, Object> map : pathPararmeters) {
            String key = map.get(COLUMN_PARAMETER_KEY).toString();
            String value = map.get(COLUMN_PARAMETER_VALUE).toString();
            path = path.replace("{" + key + "}", value);
        }

        // perform the call towards Marketo
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(propServerUrl + path))
            .setHeader("Authorization", "Bearer " + marketoAccount.getAccessToken()).build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Document newdoc = document.copy();
            Map<String, Object> data = newdoc.get(Map.class);
            
            // TODO: This is passed as text instead of JSON. Not sure what needs to be done.
            data.put("result", response.body());

            outputViews.write(newdoc, document);
        } catch (Exception e) {
            e.printStackTrace();
        }
    
    }

    public OpenApi3 getAPI() {
        if (api != null) {
            return api;
        }

        if (munchkinId == null) {
            munchkinId = marketoAccount.connect();
        }
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(MARKETO_SWAGGER_URL + munchkinId)).build();

        HttpResponse<String> response;
        try {
            // idea here is to get the user's specific Marketo instance's Swagger spec
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            File temp = File.createTempFile("tempMarketoSwagger", ".json");

            BufferedWriter bw = new BufferedWriter(new FileWriter(temp));
            bw.write(response.body());
            bw.close();
            api = new OpenApi3Parser().parse(temp, false);

            return api;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String[] getParameterSuggestions(String operationName) {
        if (operationName == null) {
            return new String[] {"No parameters to set"};
        }
        ArrayList<String> parameterList = new ArrayList<String>();
        try {
            Operation op = null;
            for (Map.Entry<String, Path> pathEntry : getAPI().getPaths().entrySet()) {
                for (Map.Entry<String, Operation> entry : pathEntry.getValue().getOperations().entrySet()) {
                    if (entry.getValue().getSummary().equals(operationName)) {
                        op = entry.getValue();
                        break;
                    }
                }
            }

            List<Parameter> parameters = op.getParameters();
            if (parameters == null) { 
                return new String[] {"No parameters to set"};
            }
            if (parameters != null) {
                for (Parameter parameter : parameters) {
                    if (parameter.getIn().toUpperCase().equals("PATH")) {
                        parameterList.add(parameter.getName());
                    }

                }
            }
        } catch (Exception e) {

        } 
        return parameterList.toArray(new String[0]);
    }
}