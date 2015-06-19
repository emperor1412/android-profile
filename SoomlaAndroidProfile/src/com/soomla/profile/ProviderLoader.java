/*
 * Copyright (C) 2012-2014 Soomla Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.soomla.profile;

import com.soomla.SoomlaUtils;
import com.soomla.profile.domain.IProvider;
import com.soomla.profile.exceptions.ProviderNotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parent class that provides functionality for dynamic loading of providers.
 */
public abstract class ProviderLoader<T extends IProvider> {

    protected boolean loadProviders(Map<IProvider.Provider, ? extends Map<String, String>> providerParams, String... providerNames) {
        List<Class<? extends T>> providerClass = tryFetchProviders(providerNames);
        if (providerClass == null || providerClass.size() == 0) {
            return false;
        }

        mProviders = new HashMap<IProvider.Provider, T>();
        for (Class<? extends T> aClass : providerClass) {
            try {
                T provider = aClass.newInstance();
                IProvider.Provider targetProvider = provider.getProvider();
                if (providerParams != null) {
                    provider.configure(providerParams.get(targetProvider));
                }
                mProviders.put(targetProvider, provider);
            } catch (Exception e) {
                String err = "Couldn't instantiate provider class. Something's totally wrong here.";
                SoomlaUtils.LogError(TAG, err);
            }
        }

        return true;
    }

    private List<Class<? extends T>> tryFetchProviders(String... providerNames) {
        List<Class<? extends T>> providers = new ArrayList<Class<? extends T>>();
        for(String providerItem : providerNames) {
            Class<? extends T> aClass = null;
            try {
                SoomlaUtils.LogDebug(TAG, "Trying to load class " + providerItem);
                aClass = (Class<? extends T>) Class.forName(providerItem);

                providers.add(aClass);
            } catch (ClassNotFoundException e) {
                SoomlaUtils.LogDebug(TAG, "Failed loading class " + providerItem + " Exception: " + e.getLocalizedMessage());
            }
        }

        return providers;
    }

    protected void handleErrorResult(String message) {
//        BusProvider.getInstance().post(new UnexpectedStoreErrorEvent(message));
        SoomlaUtils.LogError(TAG, "ERROR: " + message);
    }


    protected T getProvider(IProvider.Provider provider) throws ProviderNotFoundException {
        final T providerObj = mProviders.get(provider);
        if(providerObj == null) {
            throw new ProviderNotFoundException(provider);
        }

        return providerObj;
    }

    /** Private Members **/

    protected Map<IProvider.Provider, T> mProviders = new HashMap<IProvider.Provider, T>();

    private static String TAG = "SOOMLA ProviderLoader";
}
