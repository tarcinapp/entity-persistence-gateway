package com.tarcinapp.entitypersistencegateway.auth;

import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class PublicKeyBuilder implements IPublicKeyBuilder {

    @Value("${app.auth.rs256PublicKey:#{null}}")
    private String rs256PublicKey;

    @Override
    public Key loadPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException {

        if(rs256PublicKey == null || rs256PublicKey.equals("false"))
            return null;

        byte[] data = Base64.getDecoder().decode((rs256PublicKey.getBytes()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        KeyFactory fact = KeyFactory.getInstance("RSA");
        return fact.generatePublic(spec);
    }
    
}
