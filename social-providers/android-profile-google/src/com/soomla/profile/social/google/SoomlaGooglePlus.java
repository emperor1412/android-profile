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

package com.soomla.profile.social.google;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.os.AsyncTask;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.plus.People;
import com.google.android.gms.plus.Plus;
import com.google.android.gms.plus.PlusShare;
import com.google.android.gms.plus.model.people.Person;
import com.google.android.gms.plus.model.people.PersonBuffer;
import com.soomla.Soomla;
import com.soomla.SoomlaApp;
import com.soomla.SoomlaUtils;
import com.soomla.profile.auth.AuthCallbacks;
import com.soomla.profile.domain.UserProfile;
import com.soomla.profile.social.ISocialProvider;
import com.soomla.profile.social.SocialCallbacks;

import java.io.IOException;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Soomla wrapper for GooglePlusAPIClient.
 * This class works by creating a transparent activity (SoomlaGooglePlusActivity) and working through it.
 * This is required to correctly integrate with GooglePlus activity lifecycle events
 */
public class SoomlaGooglePlus implements ISocialProvider{

    private static final String TAG = "SOOMLA SoomlaGoogle";
    public static final String SCOPES = "https://www.googleapis.com/auth/plus.login " + "https://www.googleapis.com/auth/drive.file";
    public static final int REQUEST_CODE_TOKEN_AUTH = 125;

    private static GoogleApiClient GooglePlusAPIClient;
    private static WeakReference<Activity> WeakRefParentActivity;
    private static Provider RefProvider;
    private static AuthCallbacks.LoginListener RefLoginListener;
    private static SocialCallbacks.SocialActionListener RefSocialActionListener;

    public static final int ACTION_LOGIN = 0;
    public static final int ACTION_PUBLISH_STATUS = 1;
    public static final int ACTION_UPLOAD_IMAGE = 2;
    public static final int ACTION_PUBLISH_STORY = 3;
    public static final int ACTION_PUBLISH_STATUS_DIALOG = 4;

    /**
     * The main Soomla Google Plus activity
     * Handles GooglePlusAPIClient build and connection processes, as well as
     * it's API calls such as updating status, uploading image etc...
     */
    public static class SoomlaGooglePlusActivity extends Activity implements
            GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {
        private static final String TAG = "SOOMLA SoomlaGoogle$SoomlaGoogleActivity";

        //request codes
        private static final int REQ_SIGN_IN = 0;
        private static final int REQ_SHARE = 1;

        //google plus connectivity monitors
        private static boolean signInRequested;
        private static boolean connectionInProgress;
        private static ConnectionResult connectionResult;

        /**
         * {@inheritDoc}
         */
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            SoomlaUtils.LogDebug(TAG, "onCreate");

            Intent intent = getIntent();
            int userAction = intent.getIntExtra("action", -1);

            switch (userAction) {
                case ACTION_LOGIN: {
                    login();
                    break;
                }
                case ACTION_PUBLISH_STATUS: {
                    String status = intent.getStringExtra("status");
                    updateStatus(status);
                    break;
                }
                case ACTION_UPLOAD_IMAGE: {
                    String message = intent.getStringExtra("message");
                    String filePath = intent.getStringExtra("filepath");
                    uploadImage(message, filePath);
                    break;
                }
                case ACTION_PUBLISH_STORY: {
                    String message = intent.getStringExtra("message");
                    String name = intent.getStringExtra("name");
                    String caption = intent.getStringExtra("caption");
                    String description = intent.getStringExtra("description");
                    String link = intent.getStringExtra("link");
                    String picture = intent.getStringExtra("picture");
                    updateStory(message, name, caption, description, link, picture);
                    break;
                }
                case ACTION_PUBLISH_STATUS_DIALOG: {
                    String link = intent.getStringExtra("link");
                    updateStatusDialog(link);
                    break;
                }
            }
        }

        private void login() {
            GooglePlusAPIClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Plus.API, Plus.PlusOptions.builder().build())
                    .addScope(Plus.SCOPE_PLUS_LOGIN)
                    .build();

            if (!GooglePlusAPIClient.isConnecting()){
                signInRequested = true;
                GooglePlusAPIClient.connect();
            }
        }

        private void updateStatus(String status) {
            Intent shareIntent = new PlusShare.Builder(this)
                    .setType("text/plain")
                    .setText(status)
                    .getIntent();

            startActivityForResult(shareIntent, REQ_SHARE);
        }

        private void updateStatusDialog(String link) {
            Intent shareIntent = new PlusShare.Builder(this)
                    .setType("text/plain")
                    .setContentUrl(Uri.parse(link))
                    .getIntent();

            startActivityForResult(shareIntent, REQ_SHARE);
        }

        private void uploadImage(String message, String filePath) {
            try{
                File tmpFile = new File(filePath);
                final String photoContentUri = MediaStore.Images.Media.insertImage(
                        getContentResolver(), tmpFile.getAbsolutePath(), null, null);
                Uri uri = Uri.parse(photoContentUri);
                String mime = getContentResolver().getType(uri);

                Intent shareIntent = new PlusShare.Builder(this)
                        .setText(message)
                        .setStream(uri)
                        .setType(mime)
                        .getIntent();

                startActivityForResult(shareIntent, REQ_SHARE);
            }catch (Exception e){
                RefSocialActionListener.fail("Failed uploading image with exception: " + e.getMessage());
            }
        }

        private void updateStory(String message, String name, String caption, String description, String link, String picture) {
            try{
                //TODO: https://developers.google.com/+/mobile/android/share/interactive-post ?
                Intent shareIntent = new PlusShare.Builder(this)
                        .setType("text/plain")
                        .setText(message)
                        .setContentUrl(Uri.parse(link))
                        .getIntent();

                startActivityForResult(shareIntent, REQ_SHARE);
            }catch (Exception e){
                RefSocialActionListener.fail("Failed sharing story with exception: " + e.getMessage());
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            SoomlaUtils.LogDebug(TAG, "onConnected " + " [" + RefLoginListener + "]");
            signInRequested = false;
            RefLoginListener.success(RefProvider);
            finish();
        }

        @Override
        public void onConnectionSuspended(int i) {
            SoomlaUtils.LogDebug(TAG, "onConnectionSuspended");
            GooglePlusAPIClient.connect();
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            SoomlaUtils.LogDebug(TAG, "onConnectionFailed");

            if (result.hasResolution()) {
                if (!connectionInProgress) {
                    connectionResult = result;

                    if (signInRequested)
                        resolveSignInError();
                }
            } else {
                RefLoginListener.fail("onConnectionFailed:" + result.getErrorCode() + " [" + RefLoginListener + "]");
                finish();
            }
        }

//        @TargetApi(Build.VERSION_CODES.DONUT)
        private void resolveSignInError(){
            try {
                connectionInProgress = true;
                connectionResult.startResolutionForResult(this, REQ_SIGN_IN);

            }catch (IntentSender.SendIntentException e){
                connectionInProgress = false;
                GooglePlusAPIClient.connect();
            }
        }

        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            switch (requestCode) {
                case REQ_SIGN_IN: {
                    if (resultCode != RESULT_OK)
                        signInRequested = false;

                    connectionInProgress = false;

                    if (!GooglePlusAPIClient.isConnecting())
                        GooglePlusAPIClient.connect();
                    break;
                }

                case REQ_SHARE: {
                    if (resultCode == RESULT_OK)
                        RefSocialActionListener.success();
                    else
                        RefSocialActionListener.fail("Failed sharing with error code: " + resultCode);
                    finish();
                    break;
                }
            }
        }
    }

    @Override
    public void login(Activity parentActivity, AuthCallbacks.LoginListener loginListener) {
        SoomlaUtils.LogDebug(TAG, "login");
        WeakRefParentActivity = new WeakReference<Activity>(parentActivity);
        RefProvider = getProvider();
        RefLoginListener = loginListener;
        Intent intent = new Intent(parentActivity, SoomlaGooglePlusActivity.class);
        intent.putExtra("action", ACTION_LOGIN);
        parentActivity.startActivity(intent);
    }

    @Override
    public void logout(AuthCallbacks.LogoutListener logoutListener) {
        try {
            Plus.AccountApi.clearDefaultAccount(GooglePlusAPIClient);
            GooglePlusAPIClient.disconnect();
            logoutListener.success();
        } catch (Exception e) {
            logoutListener.fail("Failed to logout with exception: " + e.getMessage());
        }
    }

    @Override
    public boolean isLoggedIn(Activity activity) {
        return (GooglePlusAPIClient != null && GooglePlusAPIClient.isConnected());
    }

    @Override
    public void updateStatus(String status, SocialCallbacks.SocialActionListener socialActionListener) {
        RefProvider = getProvider();
        RefSocialActionListener = socialActionListener;
        Intent intent = new Intent(WeakRefParentActivity.get(), SoomlaGooglePlusActivity.class);
        intent.putExtra("action", ACTION_PUBLISH_STATUS);
        intent.putExtra("status", status);
        WeakRefParentActivity.get().startActivity(intent);
    }

    @Override
    public void updateStatusDialog(String link, SocialCallbacks.SocialActionListener socialActionListener) {
        RefProvider = getProvider();
        RefSocialActionListener = socialActionListener;
        Intent intent = new Intent(WeakRefParentActivity.get(), SoomlaGooglePlusActivity.class);
        intent.putExtra("action", ACTION_PUBLISH_STATUS_DIALOG);
        intent.putExtra("link", link);
        WeakRefParentActivity.get().startActivity(intent);
    }

    @Override
    public void updateStory(String message, String name, String caption, String description, String link, String picture, SocialCallbacks.SocialActionListener socialActionListener) {
        RefProvider = getProvider();
        RefSocialActionListener = socialActionListener;
        Intent intent = new Intent(WeakRefParentActivity.get(), SoomlaGooglePlusActivity.class);
        intent.putExtra("action", ACTION_PUBLISH_STORY);
        intent.putExtra("message", message);
        intent.putExtra("name", name);
        intent.putExtra("caption", caption);
        intent.putExtra("description", description);
        intent.putExtra("link", link);
        intent.putExtra("picture", picture);
        WeakRefParentActivity.get().startActivity(intent);
    }

    @Override
    public void updateStoryDialog(String name, String caption, String description, String link, String picture, SocialCallbacks.SocialActionListener socialActionListener) {
        //TODO:
        socialActionListener.fail("updateStoryDialog is not implemented");
    }

    @Override
    public void uploadImage(String message, String filePath, SocialCallbacks.SocialActionListener socialActionListener) {
        SoomlaUtils.LogDebug(TAG, "uploadImage");
        RefProvider = getProvider();
        RefSocialActionListener = socialActionListener;
        Intent intent = new Intent(WeakRefParentActivity.get(), SoomlaGooglePlusActivity.class);
        intent.putExtra("action", ACTION_UPLOAD_IMAGE);
        intent.putExtra("message", message);
        intent.putExtra("filepath", filePath);
        WeakRefParentActivity.get().startActivity(intent);
    }

    @Override
    public void like(Activity parentActivity, String pageName) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://plus.google.com/+" + pageName));
        parentActivity.startActivity(browserIntent);
    }



    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    @Override
    public void getUserProfile(final AuthCallbacks.UserProfileListener userProfileListener) {
        SoomlaUtils.LogDebug(TAG, "getUserProfile");
        RefProvider = getProvider();
        try{
            Person profile = Plus.PeopleApi.getCurrentPerson(GooglePlusAPIClient);
            final String email = Plus.AccountApi.getAccountName(GooglePlusAPIClient);
            final UserProfile userProfile = new UserProfile(getProvider(), profile.getId(),
                    profile.getDisplayName(), email,
                    profile.getName().getGivenName(), profile.getName().getFamilyName());
            userProfile.setAvatarLink(profile.getImage().getUrl());
            SoomlaUtils.LogDebug(TAG, "Lalala");

            AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... params) {
                    String token = null;

                    try {
//                        String link = "oauth2:" + Scopes.PLUS_LOGIN + " https://www.googleapis.com/auth/plus.profile.emails.read";
                        token = GoogleAuthUtil.getToken(SoomlaApp.getAppContext(), email, "oauth2:" + SCOPES);
                    } catch (IOException transientEx) {
                        // Network or server error, try later
                        SoomlaUtils.LogDebug(TAG, transientEx.toString());
                        userProfileListener.fail("Unable to get user profile with exception: " + transientEx.getMessage());
                    } catch (UserRecoverableAuthException e) {
                        // Recover (with e.getIntent())
                        SoomlaUtils.LogDebug(TAG, e.toString());
                        Intent recover = e.getIntent();
                        WeakRefParentActivity.get().startActivityForResult(recover, REQUEST_CODE_TOKEN_AUTH);
                        userProfileListener.fail("Unable to get user profile with exception: " + e.getMessage());
                    } catch (GoogleAuthException authEx) {
                        // The call is not ever expected to succeed
                        // assuming you have already verified that
                        // Google Play services is installed.
                        SoomlaUtils.LogDebug(TAG, authEx.toString());
                        userProfileListener.fail("Unable to get user profile with exception: " + authEx.getMessage());
                    }

                    return token;
                }

                @Override
                protected void onCancelled() {
                    super.onCancelled();
                    userProfileListener.fail("Task cancelled");
                }

                @Override
                protected void onPostExecute(String token) {
                    SoomlaUtils.LogDebug(TAG, "Access token retrieved:" + token);
                    if(token != null && !token.isEmpty()) {
                        userProfile.setAccessToken(token);
                        userProfileListener.success(userProfile);
                    }
                    else {
                        userProfileListener.fail("Can not retrieve access token, something wrong happened, please try again later");
                    }
                }

            };
            task.execute();
//            userProfileListener.success(userProfile);
        }catch (Exception e){
            userProfileListener.fail("Unable to get user profile with exception: " + e.getMessage());
        }
    }

    @Override
    public void getContacts(final SocialCallbacks.ContactsListener contactsListener) {
        RefProvider = getProvider();
        if (GooglePlusAPIClient != null && GooglePlusAPIClient.isConnected()){
            Plus.PeopleApi.loadVisible(GooglePlusAPIClient, null)
                    .setResultCallback(new ResultCallback<People.LoadPeopleResult>() {
                        @Override
                        public void onResult(People.LoadPeopleResult peopleData) {
                            if (peopleData.getStatus().getStatusCode() == CommonStatusCodes.SUCCESS) {
                                List<UserProfile> userProfiles = new ArrayList<UserProfile>();
                                PersonBuffer personBuffer = peopleData.getPersonBuffer();
                                try {
                                    int count = personBuffer.getCount();
                                    for (int i = 0; i < count; i++) {
                                        Person googleContact = personBuffer.get(i);
                                        userProfiles.add(parseGoogleContact(googleContact));
                                    }
                                    contactsListener.success(userProfiles);
                                } catch (Exception e){
                                    contactsListener.fail("Failed getting contacts with exception: " + e.getMessage());
                                }finally {
                                    personBuffer.close();
                                }
                            } else {
                                contactsListener.fail("Contact information is not available.");
                            }
                        }
                    });
        }else{
            contactsListener.fail("Failed getting contacts because because not connected to Google Plus.");
        }
    }

    @Override
    public void applyParams(Map<String, String> providerParams) {
        // Nothing to do here Google handles all needed parameters
    }

    @Override
    public void getFeed(SocialCallbacks.FeedListener feedsListener) {
        //TODO
        feedsListener.fail("getFeed is not implemented");
    }

    @Override
    public Provider getProvider() {
        return Provider.GOOGLE;
    }

    private static UserProfile parseGoogleContact(Person googleContact){
        String fullName = googleContact.getDisplayName();
        String firstName = "";
        String lastName = "";

        if (!TextUtils.isEmpty(fullName)) {
            String[] splitName = fullName.split(" ");
            if (splitName.length > 0) {
                firstName = splitName[0];
                if (splitName.length > 1) {
                    lastName = splitName[1];
                }
            }
        }

        UserProfile result = new UserProfile(RefProvider,
                parseGoogleContactInfo(googleContact.getId()),
                "", //TODO: user name
                "", //TODO: email
                firstName,
                lastName);
        result.setGender(parseGoogleContactInfo(googleContact.getGender()));
        result.setBirthday(parseGoogleContactInfo(googleContact.getBirthday()));
        result.setLanguage(parseGoogleContactInfo(googleContact.getLanguage()));
        result.setLocation(parseGoogleContactInfo(googleContact.getCurrentLocation()));
        result.setAvatarLink(parseGoogleContactInfo(googleContact.getImage().getUrl()));

        return result;
    }

    private static String parseGoogleContactInfo(Object orig){
        return (String.valueOf(orig) != null) ? String.valueOf(orig) : "";
    }
}
