package com.tarcinapp.entitypersistencegateway.auth;

import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PublicKeyFactory {

    @Value("${app.auth.rs256PublicKey:#{null}}")
    private String rs256PublicKey;

    @Bean
    public Key loadPublicKey() {

        if(rs256PublicKey == null || rs256PublicKey.equals("false"))
            return null;

        byte[] data = Base64.getDecoder().decode((rs256PublicKey.getBytes()));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        
		try {
            KeyFactory fact = KeyFactory.getInstance("RSA");
            return fact.generatePublic(spec);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {

            /*
             * This log can't contain request id because it is not generated yet.
             */
			log.error("An error occured while trying to load public key for authentication.", e);
		}
        
        return null;
    }
    
}
