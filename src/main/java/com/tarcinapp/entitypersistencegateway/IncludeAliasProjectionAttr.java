package com.tarcinapp.entitypersistencegateway;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IncludeAliasProjectionAttr {

    public static final String INCLUDE_ALIAS_PROJECTION_ATTR = "IncludeAliasProjectionAttr";

    private List<Rule> rules = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rule {
        private String genericRelation;
        private String alias;
        private String kind;
    }
}
