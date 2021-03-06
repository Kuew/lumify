package io.lumify.core.model.ontology;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.securegraph.type.GeoPoint;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class OntologyProperty {
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    public static final Pattern GEO_LOCATION_FORMAT = Pattern.compile("POINT\\((.*?),(.*?)\\)", Pattern.CASE_INSENSITIVE);
    public static final Pattern GEO_LOCATION_ALTERNATE_FORMAT = Pattern.compile("(.*?),(.*)", Pattern.CASE_INSENSITIVE);

    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public abstract String getTitle();

    public abstract String getDisplayName();

    public abstract boolean getUserVisible();

    public abstract PropertyType getDataType();

    public abstract List<PossibleValueType> getPossibleValues();

    public static JSONArray toJsonProperties(Iterable<OntologyProperty> properties) {
        JSONArray json = new JSONArray();
        for (OntologyProperty property : properties) {
            json.put(property.toJson());
        }
        return json;
    }

    public JSONObject toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("title", getTitle());
            json.put("displayName", getDisplayName());
            json.put("userVisible", getUserVisible());
            json.put("dataType", getDataType().toString());
            if (getPossibleValues() != null && getPossibleValues().size() > 0) {
                JSONArray possibleValues = new JSONArray();
                for (PossibleValueType possibleValueProperty : getPossibleValues()) {
                    JSONObject possibleValue = new JSONObject();
                    possibleValue.put("key", possibleValueProperty.getKey());
                    possibleValue.put("value", possibleValueProperty.getValue());
                    possibleValues.put(possibleValue);
                }
                json.put("possibleValues", possibleValues);
            }
            return json;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public Object convertString(String valueStr) throws ParseException {
        PropertyType dataType = getDataType();
        Object value = valueStr;
        switch (dataType) {
            case DATE:
                value = DATE_FORMAT.parse(valueStr);
                break;
            case GEO_LOCATION:
                value = parseGeoLocation(valueStr);
                break;
            case CURRENCY:
                value = Double.parseDouble(valueStr);
                break;
            case DOUBLE:
                value = Double.parseDouble(valueStr);
                break;
            case BOOLEAN:
                value = Boolean.parseBoolean(valueStr);
                break;
        }
        return value;
    }

    protected Object parseGeoLocation(String valueStr) {
        Matcher match = GEO_LOCATION_FORMAT.matcher(valueStr);
        if (match.find()) {
            double latitude = Double.parseDouble(match.group(1).trim());
            double longitude = Double.parseDouble(match.group(2).trim());
            return new GeoPoint(latitude, longitude);
        }
        match = GEO_LOCATION_ALTERNATE_FORMAT.matcher(valueStr);
        if (match.find()) {
            double latitude = Double.parseDouble(match.group(1).trim());
            double longitude = Double.parseDouble(match.group(2).trim());
            return new GeoPoint(latitude, longitude);
        }
        throw new RuntimeException("Could not parse location: " + valueStr);
    }
}
