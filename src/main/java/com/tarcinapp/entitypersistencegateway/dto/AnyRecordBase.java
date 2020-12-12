package com.tarcinapp.entitypersistencegateway.dto;

import java.time.ZonedDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_NULL)
public class AnyRecordBase {
    
    private String id;
    private String kind;
    private String name;
    private String slug;
    private List<String> ownerUsers;
    private List<String> ownerGroups;
    private String visibility;
    private ZonedDateTime creationDateTime;
    private ZonedDateTime validFromDateTime;
    private ZonedDateTime validUntilDateTime;
    private ZonedDateTime lastUpdatedDateTime;

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

    public ZonedDateTime  getCreationDateTime() {
        return this.creationDateTime;
    }

    public ZonedDateTime getValidUntilDateTime() {
        return this.validUntilDateTime;
    }

    public void setValidUntilDateTime(ZonedDateTime validUntilDateTime) {
        this.validUntilDateTime = validUntilDateTime;
    }

    public void setCreationDateTime(ZonedDateTime  creationDateTime) {
        this.creationDateTime = creationDateTime;
    }

    public ZonedDateTime  getValidFromDateTime() {
        return this.validFromDateTime;
    }

    public void setValidFromDateTime(ZonedDateTime  validFromDateTime) {
        this.validFromDateTime = validFromDateTime;
    }

    public ZonedDateTime  getLastUpdatedDateTime() {
        return this.lastUpdatedDateTime;
    }

    public void setLastUpdatedDateTime(ZonedDateTime  lastUpdatedDateTime) {
        this.lastUpdatedDateTime = lastUpdatedDateTime;
    }
    
}
