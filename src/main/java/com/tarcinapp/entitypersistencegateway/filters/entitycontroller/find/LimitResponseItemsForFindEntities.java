package com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find;

import java.util.regex.Pattern;

import com.tarcinapp.entitypersistencegateway.filters.entitycontroller.find.LimitResponseFieldsForFindEntities.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

@Component
public class LimitResponseItemsForFindEntities
        extends AbstractGatewayFilterFactory<LimitResponseItemsForFindEntities.Config> {

    private Logger logger = LogManager.getLogger(LimitResponseItemsForFindEntities.class);

    private final static String GATEWAY_CONTEXT_ATTR = "GatewayContext";

    private final static Pattern FIELD_QUERY_PATTERN = Pattern.compile("filter\\[fields\\]\\[([^\\]]+)\\]");

    public LimitResponseItemsForFindEntities() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        // TODO Auto-generated method stub
        return null;
    }
    
    public static class Config {

    }
}
