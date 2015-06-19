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

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;

import com.soomla.BusProvider;
import com.soomla.SoomlaApp;
import com.soomla.SoomlaUtils;
import com.soomla.profile.domain.IProvider;
import com.soomla.profile.domain.UserProfile;
import com.soomla.profile.events.social.GetContactsFailedEvent;
import com.soomla.profile.events.social.GetContactsFinishedEvent;
import com.soomla.profile.events.social.GetContactsStartedEvent;
import com.soomla.profile.events.social.GetFeedFailedEvent;
import com.soomla.profile.events.social.GetFeedFinishedEvent;
import com.soomla.profile.events.social.GetFeedStartedEvent;
import com.soomla.profile.events.social.SocialActionFailedEvent;
import com.soomla.profile.events.social.SocialActionFinishedEvent;
import com.soomla.profile.events.social.SocialActionStartedEvent;
import com.soomla.profile.exceptions.ProviderNotFoundException;
import com.soomla.profile.social.ISocialProvider;
import com.soomla.profile.social.SocialCallbacks;
import com.soomla.rewards.Reward;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A class that loads all social providers and performs social
 * actions on with them.  This class wraps the provider's social
 * actions in order to connect them to user profile data and rewards.
 * <p/>
 * Inheritance: {@link com.soomla.profile.SocialController} >
 * {@link com.soomla.profile.AuthController} >
 * {@link com.soomla.profile.ProviderLoader}
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
public class SocialController extends AuthController<ISocialProvider> {

    /**
     * Constructor
     * <p/>
     * Loads all social providers
     * * @param usingExternalProvider {@link SoomlaProfile#initialize}
     */
    public SocialController(boolean usingExternalProvider, Map<IProvider.Provider, ? extends Map<String, String>> providerParams) {
        super(usingExternalProvider, providerParams);
        if (!usingExternalProvider && !loadProviders(providerParams, "com.soomla.profile.social.facebook.SoomlaFacebook",
                "com.soomla.profile.social.google.SoomlaGooglePlus",
                "com.soomla.profile.social.twitter.SoomlaTwitter")) {
            String msg = "You don't have a ISocialProvider service attached. " +
                    "Decide which ISocialProvider you want, add it to AndroidManifest.xml " +
                    "and add its jar to the path.";
            SoomlaUtils.LogDebug(TAG, msg);
        }
    }

    /**
     * Shares the given status to the user's feed
     *
     * @param provider the provider to use
     * @param status   the text to share
     * @param payload  a String to receive when the function returns.
     * @param reward   the reward to grant for sharing
     * @param activity If defined, confirmation confirmation dialog will be shown before the action
     * @param customMessage The message to show in the confirmation dialog, if it's not provided, default value will be used.
     * @throws ProviderNotFoundException
     */
    public void updateStatus(final IProvider.Provider provider, final String status, final String payload, final Reward reward, final Activity activity, String customMessage) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);

        if (activity != null) {
            final String message = customMessage != null ? customMessage :
                    String.format("Are you sure you want to publish this message to %s: %s?",
                            provider.toString(), status);

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(activity)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle("Confirmation")
                            .setMessage(message)
                            .setPositiveButton("yes", new DialogInterface.OnClickListener() {

                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    internalUpdateStatus(socialProvider, provider, status, payload, reward);
                                }

                            })
                            .setNegativeButton("no", null)
                            .show();
                }
            });
        } else {
            internalUpdateStatus(socialProvider, provider, status, payload, reward);
        }
    }

    /**
     * Shares the given status to the user's feed.
     * Using the provider's native dialog (when available).
     *
     * @param provider the provider to use
     * @param link     the text to share
     * @param payload  a String to receive when the function returns.
     * @param reward   the reward to grant for sharing
     * @throws ProviderNotFoundException
     */
    public void updateStatusDialog(final IProvider.Provider provider, String link, final String payload, final Reward reward) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);

        final ISocialProvider.SocialActionType updateStatusType = ISocialProvider.SocialActionType.UPDATE_STATUS;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, updateStatusType, payload));
        socialProvider.updateStatusDialog(link, new SocialCallbacks.SocialActionListener() {
            @Override
            public void success() {
                BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, updateStatusType, payload));

                if (reward != null) {
                    reward.give();
                }
            }

            @Override
            public void fail(String message) {
                BusProvider.getInstance().post(new SocialActionFailedEvent(provider, updateStatusType, message, payload));
            }
        });
    }

    /**
     * Shares a story to the user's feed.  This is very oriented for Facebook.
     *
     * @param provider    The provider on which to update user's story
     * @param message     The main text which will appear in the story
     * @param name        The headline for the link which will be integrated in the
     *                    story
     * @param caption     The sub-headline for the link which will be
     *                    integrated in the story
     * @param description description The description for the link which will be
     *                    integrated in the story
     * @param link        The link which will be integrated into the user's story
     * @param picture     a Link to a picture which will be featured in the link
     * @param payload  a String to receive when the function returns.
     * @param reward      The reward which will be granted to the user upon a
     *                    successful update
     * @param activity If defined, confirmation confirmation dialog will be shown before the action
     * @param customMessage The message to show in the confirmation dialog, if it's not provided, default value will be used.
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void updateStory(final IProvider.Provider provider, final String message, final String name, final String caption, final String description,
                            final String link, final String picture, final String payload, final Reward reward, final Activity activity, String customMessage) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);

        if (activity != null) {
            final String messageToShow = customMessage != null ? customMessage :
                    String.format("Are you sure you want to publish to %s?", provider.toString());

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(activity)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle("Confirmation")
                            .setMessage(messageToShow)
                            .setPositiveButton("yes", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    internalUpdateStory(provider, message, name, caption, description, link, picture, payload, reward, socialProvider);
                                }
                            })
                            .setNegativeButton("no", null)
                            .show();
                }
            });
        } else {
            internalUpdateStory(provider, message, name, caption, description, link, picture, payload, reward, socialProvider);
        }
    }

    /**
     * Shares a story to the user's feed.  This is very oriented for Facebook.
     * Using the provider's native dialog (when available).
     *
     * @param provider    The provider on which to update user's story
     * @param name        The headline for the link which will be integrated in the
     *                    story
     * @param caption     The sub-headline for the link which will be
     *                    integrated in the story
     * @param description description The description for the link which will be
     *                    integrated in the story
     * @param link        The link which will be integrated into the user's story
     * @param picture     a Link to a picture which will be featured in the link
     * @param payload  a String to receive when the function returns.
     * @param reward      The reward which will be granted to the user upon a
     *                    successful update
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void updateStoryDialog(final IProvider.Provider provider, String name, String caption, String description,
                                  String link, String picture, final String payload, final Reward reward) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);

        final ISocialProvider.SocialActionType updateStoryType = ISocialProvider.SocialActionType.UPDATE_STORY;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, updateStoryType, payload));
        socialProvider.updateStoryDialog(name, caption, description, link, picture,
                new SocialCallbacks.SocialActionListener() {
                    @Override
                    public void success() {
                        BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, updateStoryType, payload));

                        if (reward != null) {
                            reward.give();
                        }
                    }

                    @Override
                    public void fail(String message) {
                        BusProvider.getInstance().post(new SocialActionFailedEvent(provider, updateStoryType, message, payload));
                    }
                }
        );
    }

    /**
     * Shares a photo to the user's feed.  This is very oriented for Facebook.
     *
     * @param provider The provider to use
     * @param message  A text that will accompany the image
     * @param filePath The desired image's location on the device
     * @param payload  a String to receive when the function returns.
     * @param reward   The reward to grant for sharing the photo
     * @param activity If defined, confirmation confirmation dialog will be shown before the action
     * @param customMessage The message to show in the confirmation dialog, if it's not provided, default value will be used.
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void uploadImage(final IProvider.Provider provider,
                            final String message, final String filePath,
                            final String payload, final Reward reward,
                            final Activity activity, String customMessage) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);

        if (activity != null) {
            final String messageToShow = customMessage != null ? customMessage :
                    String.format("Are you sure you want to upload image to %s?", provider.toString());

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(activity)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle("Confirmation")
                            .setMessage(messageToShow)
                            .setPositiveButton("yes", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    internalUploadImage(provider, message, filePath, payload, reward, socialProvider);
                                }
                            })
                            .setNegativeButton("no", null)
                            .show();
                }
            });
        } else {
            internalUploadImage(provider, message, filePath, payload, reward, socialProvider);
        }
    }

    /**
     * Upload image using Bitmap
     *
     * @param provider    The provider to use
     * @param message     A text that will accompany the image
     * @param fileName    The desired image's file name
     * @param bitmap      The image to share
     * @param jpegQuality Image quality, number from 0 to 100. 0 meaning compress for small size, 100 meaning compress for max quality.
                          Some formats, like PNG which is lossless, will ignore the quality setting
     * @param payload     a String to receive when the function returns.
     * @param reward      The reward to grant for sharing the photo
     * @param activity    If defined, confirmation confirmation dialog will be shown before the action
     * @param customMessage The message to show in the confirmation dialog, if it's not provided, default value will be used.
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    public void uploadImage(final IProvider.Provider provider,
                            final String message, final String fileName, final Bitmap bitmap, final int jpegQuality,
                            final String payload, final Reward reward, final Activity activity, String customMessage) throws ProviderNotFoundException {

        final ISocialProvider socialProvider = getProvider(provider);

        if (activity != null) {
            final String messageToShow = customMessage != null ? customMessage :
                    String.format("Are you sure you want to upload image to %s?", provider.toString());

            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(activity)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle("Confirmation")
                            .setMessage(messageToShow)
                            .setPositiveButton("yes", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    internalUploadImage(provider, message, fileName, bitmap, jpegQuality, payload, reward, socialProvider);
                                }
                            })
                            .setNegativeButton("no", null)
                            .show();
                }
            });
        } else {
            internalUploadImage(provider, message, fileName, bitmap, jpegQuality, payload, reward, socialProvider);
        }
    }

    /**
     * Upload image using a File handler
     *
     * @param provider    The provider to use
     * @param message     A text that will accompany the image
     * @param file        An image file handler
     * @param payload     a String to receive when the function returns.
     * @param reward      The reward to grant for sharing the photo
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void uploadImage(final IProvider.Provider provider,
                            String message, File file,
                            final String payload, final Reward reward) throws ProviderNotFoundException {
        if (file == null){
            SoomlaUtils.LogError(TAG, "(uploadImage) File is null!");
            return;
        }

        uploadImage(provider, message, file.getAbsolutePath(), payload, reward, null, null);
    }

    /**
     * Fetches the user's contact list
     *
     * @param provider The provider to use
     * @param fromStart Should we reset pagination or request the next page
     * @param payload  a String to receive when the function returns.
     * @param reward   The reward to grant
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void getContacts(final IProvider.Provider provider,
                            final boolean fromStart, final String payload, final Reward reward) throws ProviderNotFoundException {

        final ISocialProvider socialProvider = getProvider(provider);

        final ISocialProvider.SocialActionType getContactsType = ISocialProvider.SocialActionType.GET_CONTACTS;
        BusProvider.getInstance().post(new GetContactsStartedEvent(provider, getContactsType, fromStart, payload));
        socialProvider.getContacts(fromStart, new SocialCallbacks.ContactsListener() {
                                       @Override
                                       public void success(List<UserProfile> contacts, boolean hasMore) {
                                           BusProvider.getInstance().post(new GetContactsFinishedEvent(provider, getContactsType, contacts, payload, hasMore));

                                           if (reward != null) {
                                               reward.give();
                                           }
                                       }

                                       @Override
                                       public void fail(String message) {
                                           BusProvider.getInstance().post(new GetContactsFailedEvent(provider, getContactsType, message, fromStart, payload));
                                       }
                                   }
        );
    }

    /**
     * Fetches the user's feed.
     *
     * @param provider The provider to use
     * @param fromStart Should we reset pagination or request the next page
     * @param payload  a String to receive when the function returns.
     * @param reward   The reward to grant
     * @throws ProviderNotFoundException if the supplied provider is not supported by the framework
     */
    public void getFeed(final IProvider.Provider provider,
                        final Boolean fromStart, final String payload, final Reward reward) throws ProviderNotFoundException {

        final ISocialProvider socialProvider = getProvider(provider);

        final ISocialProvider.SocialActionType getFeedType = ISocialProvider.SocialActionType.GET_FEED;
        BusProvider.getInstance().post(new GetFeedStartedEvent(provider, getFeedType, fromStart, payload));
        socialProvider.getFeed(fromStart, new SocialCallbacks.FeedListener() {
                                   @Override
                                   public void success(List<String> feedPosts, boolean hasMore) {
                                       BusProvider.getInstance().post(new GetFeedFinishedEvent(provider, getFeedType, feedPosts, payload, hasMore));

                                       if (reward != null) {
                                           reward.give();
                                       }
                                   }

                                   @Override
                                   public void fail(String message) {
                                       BusProvider.getInstance().post(new GetFeedFailedEvent(provider, getFeedType, message, fromStart, payload));
                                   }
                               }
        );
    }

    /**
     * Opens up a provider page to "like" (external), and grants the user the supplied reward
     *
     * @param activity The parent activity
     * @param provider The provider to use
     * @param pageId The page to open up
     * @param reward   The reward to grant
     * @throws ProviderNotFoundException if the supplied provider is not
     *                                   supported by the framework
     */
    public void like(final Activity activity, final IProvider.Provider provider,
                     String pageId,
                     final Reward reward) throws ProviderNotFoundException {
        final ISocialProvider socialProvider = getProvider(provider);
        socialProvider.like(activity, pageId);

        if (reward != null) {
            reward.give();
        }
    }

    private class TempImage {

        public TempImage(String aFileName, Bitmap aBitmap, int aJpegQuality){
            this.mFileName = aFileName;
            this.mImageBitmap = aBitmap;
            this.mJpegQuality = aJpegQuality;
        }

        protected File writeToStorage() throws IOException {
            SoomlaUtils.LogDebug(TAG, "Saving temp image file.");

            File tempDir = new File(getTempImageDir());
            tempDir.mkdirs();
            BufferedOutputStream bos = null;

            try{
                File file = new File(tempDir.toString() + this.mFileName);
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                bos = new BufferedOutputStream(fileOutputStream);

                String extension = this.mFileName.substring((this.mFileName.lastIndexOf(".") + 1), this.mFileName.length());
                Bitmap.CompressFormat format = ("png".equals(extension) ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG);

                this.mImageBitmap.compress(format, this.mJpegQuality, bos);

                bos.flush();
                return file;

            } catch (Exception e){
                SoomlaUtils.LogError(TAG, "(save) Failed saving temp image file: " + this.mFileName + " with error: " + e.getMessage());

            } finally {
                if (bos != null){
                    bos.close();
                }
            }

            return null;
        }

        private String getTempImageDir(){
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){
                SoomlaUtils.LogDebug(TAG, "(getTempImageDir) External storage not ready.");
                return null;
            }

            ContextWrapper soomContextWrapper = new ContextWrapper(SoomlaApp.getAppContext());

            return Environment.getExternalStorageDirectory() + soomContextWrapper.getFilesDir().getPath() + "/temp/";
        }

        final String TAG = "TempImageFile";
        Bitmap mImageBitmap;
        String mFileName;
        int mJpegQuality;
    }

    private void internalUpdateStatus(ISocialProvider socialProvider, final IProvider.Provider provider, String status, final String payload, final Reward reward) {
        final ISocialProvider.SocialActionType updateStatusType = ISocialProvider.SocialActionType.UPDATE_STATUS;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, updateStatusType, payload));
        socialProvider.updateStatus(status, new SocialCallbacks.SocialActionListener() {
            @Override
            public void success() {
                BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, updateStatusType, payload));

                if (reward != null) {
                    reward.give();
                }
            }

            @Override
            public void fail(String message) {
                BusProvider.getInstance().post(new SocialActionFailedEvent(provider, updateStatusType, message, payload));
            }
        });
    }

    private void internalUpdateStory(final IProvider.Provider provider, String message, String name, String caption, String description, String link, String picture, final String payload, final Reward reward, ISocialProvider socialProvider) {
        final ISocialProvider.SocialActionType updateStoryType = ISocialProvider.SocialActionType.UPDATE_STORY;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, updateStoryType, payload));
        socialProvider.updateStory(message, name, caption, description, link, picture,
                new SocialCallbacks.SocialActionListener() {
                    @Override
                    public void success() {
                        BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, updateStoryType, payload));

                        if (reward != null) {
                            reward.give();
                        }
                    }

                    @Override
                    public void fail(String message) {
                        BusProvider.getInstance().post(new SocialActionFailedEvent(provider, updateStoryType, message, payload));
                    }
                }
        );
    }

    private void internalUploadImage(final IProvider.Provider provider, String message, String filePath, final String payload, final Reward reward, ISocialProvider socialProvider) {
        final ISocialProvider.SocialActionType uploadImageType = ISocialProvider.SocialActionType.UPLOAD_IMAGE;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, uploadImageType, payload));
        socialProvider.uploadImage(message, filePath, new SocialCallbacks.SocialActionListener() {
                    @Override
                    public void success() {
                        BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, uploadImageType, payload));

                        if (reward != null) {
                            reward.give();
                        }
                    }

                    @Override
                    public void fail(String message) {
                        BusProvider.getInstance().post(new SocialActionFailedEvent(provider, uploadImageType, message, payload));
                    }
                }
        );
    }

    private void internalUploadImage(final IProvider.Provider provider, final String message, String fileName, Bitmap bitmap, int jpegQuality, final String payload, final Reward reward, final ISocialProvider socialProvider) {
        final ISocialProvider.SocialActionType uploadImageType = ISocialProvider.SocialActionType.UPLOAD_IMAGE;
        BusProvider.getInstance().post(new SocialActionStartedEvent(provider, uploadImageType, payload));

        //Save a temp image to external storage in background and try to upload it when finished
        new AsyncTask<TempImage, Object, File>() {

            @Override
            protected File doInBackground(TempImage... params) {
                try {
                    return params[0].writeToStorage();
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final File result){
                if (result == null){
                    BusProvider.getInstance().post(new SocialActionFailedEvent(provider, uploadImageType, "No image file to upload.", payload));
                    return;
                }

                socialProvider.uploadImage(message, result.getAbsolutePath(), new SocialCallbacks.SocialActionListener() {
                            @Override
                            public void success() {
                                BusProvider.getInstance().post(new SocialActionFinishedEvent(provider, uploadImageType, payload));

                                if (reward != null) {
                                    reward.give();
                                }

                                result.delete();
                            }

                            @Override
                            public void fail(String message) {
                                BusProvider.getInstance().post(new SocialActionFailedEvent(provider, uploadImageType, message, payload));

                                result.delete();
                            }
                        }
                );
            }
        }.execute(new TempImage(fileName, bitmap, jpegQuality));
    }

    private static final String TAG = "SOOMLA SocialController";
}

