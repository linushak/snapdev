/*
 * SnapLogic - Data Integration
 *
 * Copyright (C) 2016, SnapLogic, Inc.  All rights reserved.
 *
 * This program is licensed under the terms of
 * the SnapLogic Commercial Subscription agreement.
 *
 * "SnapLogic" is a trademark of SnapLogic, Inc.
 * 
 * @author lhakansson@snaplogic.com
 */

package com.snaplogic.snaps;

import com.snaplogic.account.api.Account;
import com.snaplogic.account.api.AccountType;
import com.snaplogic.account.api.ValidatableAccount;
import com.snaplogic.account.api.capabilities.AccountCategory;
import com.snaplogic.api.ExecutionException;
import com.snaplogic.common.properties.SnapProperty;

import com.snaplogic.common.properties.builders.PropertyBuilder;
import com.snaplogic.snap.api.PropertyValues;
import com.snaplogic.snap.api.capabilities.General;
import com.snaplogic.snap.api.capabilities.Version;

@General(title = "Marketo Account")
@Version(snap = 1)
@AccountCategory(type = AccountType.CUSTOM)
public class MarketoAccount implements Account<String>, ValidatableAccount<String> {

    protected static final String MUNCHKIN_ID = "MunchkinId";
    protected static final String ACCESS_TOKEN = "AccessToken";

    private String munchkinId;
    private String accessToken;

    @Override
    public void defineProperties(PropertyBuilder propertyBuilder) {
        propertyBuilder.describe(MUNCHKIN_ID, "Munchin ID", "Your Marketo Munchin ID").required()
                .sensitivity(SnapProperty.SensitivityLevel.MEDIUM).add();
        propertyBuilder.describe(ACCESS_TOKEN, "Access Token", "Your Marketo Access Token").required()
                .sensitivity(SnapProperty.SensitivityLevel.MEDIUM).obfuscate().add();
    }

    @Override
    public void configure(PropertyValues propertyValues) {
        munchkinId = propertyValues.get(MUNCHKIN_ID);
        accessToken = propertyValues.get(ACCESS_TOKEN);
    }

    @Override
    public String connect() throws ExecutionException {
        return munchkinId;
    }

    @Override
    public void disconnect() throws ExecutionException {
        // no-op
    }

    public String getAccessToken() {
        return accessToken;
    }

}