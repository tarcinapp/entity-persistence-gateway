package com.tarcinapp.entitypersistencegateway.auth;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

public interface IPublicKeyBuilder {
    Key loadPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException;
}
