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

    private String id;
    private String kind;
    private String name;
    private String slug;
    private String visibility;
    private List<String> ownerUsers;
    private List<String> ownerGroups;
    private int ownerUsersCount;
    private int ownerGroupsCount;
    private List<String> viewerUsers;
    private List<String> viewerGroups;
    private int viewerUsersCount;
    private int viewerGroupsCount;
    private String lastUpdatedBy;
    private String createdBy;
    private Integer version;
    private String idempotencyKey;
    private String application;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime creationDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime validFromDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime validUntilDateTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
    private ZonedDateTime lastUpdatedDateTime;

    @JsonIgnore
    private Map<String, Object> properties;

    public AnyRecordBase() {
        properties = new HashMap<>();
    }

    public String getKind() {
        return this.kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return this.slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public List<String> getOwnerUsers() {
        return this.ownerUsers;
    }

    public void setOwnerUsers(List<String> ownerUsers) {
        this.ownerUsers = ownerUsers;
    }

    public List<String> getOwnerGroups() {
        return this.ownerGroups;
    }

    public void setOwnerGroups(List<String> ownerGroups) {
        this.ownerGroups = ownerGroups;
    }

    public String getVisibility() {
        return this.visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public ZonedDateTime getCreationDateTime() {
        return this.creationDateTime;
    }

    public ZonedDateTime getValidUntilDateTime() {
        return this.validUntilDateTime;
    }

    public void setValidUntilDateTime(ZonedDateTime validUntilDateTime) {
        this.validUntilDateTime = validUntilDateTime;
    }

    public void setCreationDateTime(ZonedDateTime creationDateTime) {
        this.creationDateTime = creationDateTime;
    }

    public ZonedDateTime getValidFromDateTime() {
        return this.validFromDateTime;
    }

    public void setValidFromDateTime(ZonedDateTime validFromDateTime) {
        this.validFromDateTime = validFromDateTime;
    }

    public ZonedDateTime getLastUpdatedDateTime() {
        return this.lastUpdatedDateTime;
    }

    public void setLastUpdatedDateTime(ZonedDateTime lastUpdatedDateTime) {
        this.lastUpdatedDateTime = lastUpdatedDateTime;
    }

    public int getOwnerUsersCount() {
        return this.ownerUsersCount;
    }

    public void setOwnerUsersCount(int ownerUsersCount) {
        this.ownerUsersCount = ownerUsersCount;
    }

    public int getOwnerGroupsCount() {
        return this.ownerGroupsCount;
    }

    public void setOwnerGroupsCount(int ownerGroupsCount) {
        this.ownerGroupsCount = ownerGroupsCount;
    }

    public String getLastUpdatedBy() {
        return this.lastUpdatedBy;
    }

    public void setLastUpdatedBy(String lastUpdatedBy) {
        this.lastUpdatedBy = lastUpdatedBy;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

        public List<String> getViewerUsers() {
        return this.viewerUsers;
    }

    public void setViewerUsers(List<String> viewerUsers) {
        this.viewerUsers = viewerUsers;
    }

    public List<String> getViewerGroups() {
        return this.viewerGroups;
    }

    public void setViewerGroups(List<String> viewerGroups) {
        this.viewerGroups = viewerGroups;
    }

    public int getViewerUsersCount() {
        return viewerUsersCount;
    }

    public void setViewerUsersCount(int viewerUsersCount) {
        this.viewerUsersCount = viewerUsersCount;
    }

    public int getViewerGroupsCount() {
        return viewerGroupsCount;
    }

    public void setViewerGroupsCount(int viewerGroupsCount) {
        this.viewerGroupsCount = viewerGroupsCount;
    }

    @JsonAnySetter
    public void setCustomFields(String property, Object value) {
        properties.put(property, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getCustomFields() {
        return this.properties;
    }
}
