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

@JsonInclude(Include.NON_NULL)
public class AnyRecordBase {

    private String _id;
    private String _kind;
    private String _name;
    private String _slug;
    private String _visibility;
    private List<String> _ownerUsers;
    private List<String> _ownerGroups;
    private int _ownerUsersCount;
    private int _ownerGroupsCount;
    private List<String> _viewerUsers;
    private List<String> _viewerGroups;
    private int _viewerUsersCount;
    private int _viewerGroupsCount;
    private String _lastUpdatedBy;
    private String _createdBy;
    private Integer _version;
    private String _idempotencyKey;
    private String _application;

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

    public String get_kind() {
        return this._kind;
    }

    public void set_kind(String _kind) {
        this._kind = _kind;
    }

    public String get_id() {
        return this._id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public String get_name() {
        return this._name;
    }

    public void set_name(String _name) {
        this._name = _name;
    }

    public String get_slug() {
        return this._slug;
    }

    public void set_slug(String _slug) {
        this._slug = _slug;
    }

    public List<String> get_ownerUsers() {
        return this._ownerUsers;
    }

    public void set_ownerUsers(List<String> _ownerUsers) {
        this._ownerUsers = _ownerUsers;
    }

    public List<String> get_ownerGroups() {
        return this._ownerGroups;
    }

    public void set_ownerGroups(List<String> _ownerGroups) {
        this._ownerGroups = _ownerGroups;
    }

    public String get_visibility() {
        return this._visibility;
    }

    public void set_visibility(String _visibility) {
        this._visibility = _visibility;
    }

    public ZonedDateTime get_createdDateTime() {
        return this._createdDateTime;
    }

    public ZonedDateTime get_validUntilDateTime() {
        return this._validUntilDateTime;
    }

    public void set_validUntilDateTime(ZonedDateTime _validUntilDateTime) {
        this._validUntilDateTime = _validUntilDateTime;
    }

    public void set_createdDateTime(ZonedDateTime _createdDateTime) {
        this._createdDateTime = _createdDateTime;
    }

    public ZonedDateTime get_validFromDateTime() {
        return this._validFromDateTime;
    }

    public void set_validFromDateTime(ZonedDateTime _validFromDateTime) {
        this._validFromDateTime = _validFromDateTime;
    }

    public ZonedDateTime get_lastUpdatedDateTime() {
        return this._lastUpdatedDateTime;
    }

    public void set_lastUpdatedDateTime(ZonedDateTime _lastUpdatedDateTime) {
        this._lastUpdatedDateTime = _lastUpdatedDateTime;
    }

    public int get_ownerUsersCount() {
        return this._ownerUsersCount;
    }

    public void set_ownerUsersCount(int _ownerUsersCount) {
        this._ownerUsersCount = _ownerUsersCount;
    }

    public int get_ownerGroupsCount() {
        return this._ownerGroupsCount;
    }

    public void set_ownerGroupsCount(int _ownerGroupsCount) {
        this._ownerGroupsCount = _ownerGroupsCount;
    }

    public String get_lastUpdatedBy() {
        return this._lastUpdatedBy;
    }

    public void set_lastUpdatedBy(String _lastUpdatedBy) {
        this._lastUpdatedBy = _lastUpdatedBy;
    }

    public String get_createdBy() {
        return this._createdBy;
    }

    public void set_createdBy(String _createdBy) {
        this._createdBy = _createdBy;
    }

    public Integer get_version() {
        return _version;
    }

    public void set_version(Integer _version) {
        this._version = _version;
    }

    public String get_idempotencyKey() {
        return _idempotencyKey;
    }

    public void set_idempotencyKey(String _idempotencyKey) {
        this._idempotencyKey = _idempotencyKey;
    }

    public String get_application() {
        return _application;
    }

    public void set_application(String _application) {
        this._application = _application;
    }

        public List<String> get_viewerUsers() {
        return this._viewerUsers;
    }

    public void set_viewerUsers(List<String> _viewerUsers) {
        this._viewerUsers = _viewerUsers;
    }

    public List<String> get_viewerGroups() {
        return this._viewerGroups;
    }

    public void set_viewerGroups(List<String> _viewerGroups) {
        this._viewerGroups = _viewerGroups;
    }

    public int get_viewerUsersCount() {
        return _viewerUsersCount;
    }

    public void set_viewerUsersCount(int _viewerUsersCount) {
        this._viewerUsersCount = _viewerUsersCount;
    }

    public int get_viewerGroupsCount() {
        return _viewerGroupsCount;
    }

    public void set_viewerGroupsCount(int _viewerGroupsCount) {
        this._viewerGroupsCount = _viewerGroupsCount;
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
