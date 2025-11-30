package com.tarcinapp.entitypersistencegateway.dto;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;

@JsonInclude(Include.NON_NULL)
@Data
public class AnyRecordBase {

    private String _id;
    private String _kind;
    private String _name;
    private String _slug;
    private String _visibility;
    private List<String> _ownerUsers;
    private List<String> _ownerGroups;
    private List<String> _viewerUsers;
    private List<String> _viewerGroups;
    private String _lastUpdatedBy;
    private String _createdBy;
    private Integer _version;
    private String _idempotencyKey;
    private String _application;
    private Map<String, Object> _relationMetadata;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime _createdDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime _validFromDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime _validUntilDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime _lastUpdatedDateTime;

    @JsonIgnore
    private Map<String, Object> _properties;

    public AnyRecordBase() {
        _properties = new HashMap<>();
    }


    @JsonAnySetter
    public void setCustomFields(String property, Object value) {
        _properties.put(property, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getCustomFields() {
        return this._properties;
    }
}
