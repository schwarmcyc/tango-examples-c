/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.examples.cpp.helloareadescription;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * This activity is called after the user selects the area description configuration in the
 * {@code StartActivity}.
 * It will start the Tango with the selected configuration and display some debugging information
 * on the screen to illustrate the running state of the system.
 * <p/>
 * This activity triggers functions in the C++ layer to initialize and configure the Tango using
 * the area description configuration chosen by the user and handles the basic lifecycle of the
 * activity while the Tango is running.
 */
public class AreaDescriptionActivity extends Activity implements
        SetAdfNameDialog.CallbackListener, SaveAdfTask.SaveAdfListener {
    // The minimum Tango Core version required from this application.
    private static final int MIN_TANGO_CORE_VERSION = 6804;

    // Tag for debug logging.
    private static final String TAG = AreaDescriptionActivity.class.getSimpleName();

    // The interval at which we'll update our UI debug text in milliseconds.
    // This is the rate at which we query our native wrapper around the tango
    // service for pose and event information.
    private static final int UPDATE_UI_INTERVAL_MS = 100;

    private TextView mAdfUuidTextView;
    private TextView mRelocalizationTextView;

    // Flag that controls whether user wants to run area learning mode.
    private boolean mIsAreaLearningEnabled = false;

    // Flag that controls whether user wants to load the latest ADF file.
    private boolean mIsLoadingADF = false;

    // A flag to check if the Tango Service is connected. This flag avoids the
    // program attempting to disconnect from the service while it is not
    // connected.This is especially important in the onPause() callback for the
    // activity class.
    private volatile boolean mIsConnectedService = false;

    // Screen size for normalizing the touch input for orbiting the render camera.
    private Point mScreenSize = new Point();

    // Long-running task to save an ADF.
    private SaveAdfTask mSaveAdfTask;

    // Handles the debug text UI update loop.
    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        queryDataFromStartActivity();
        setupUiComponents();

        // Check that the installed version of the Tango Core is up to date.
        if (!TangoJNINative.initialize(this, MIN_TANGO_CORE_VERSION)) {
            Toast.makeText(this, "Tango Core is out of date, please update in Play Store",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Setup the configuration for the TangoService, passing in our setting
        // for the auto-recovery option.
        TangoJNINative.setupConfig(mIsAreaLearningEnabled, mIsLoadingADF);

        // Connect the onPoseAvailable callback.
        TangoJNINative.connectCallbacks();

        // Connect to Tango Service (returns true on success).
        // Starts Motion Tracking and Area Learning.
        if (TangoJNINative.connect()) {
            // Display loaded ADF's UUID.
            mAdfUuidTextView.setText(TangoJNINative.getLoadedAdfUuidString());

            // Set the connected service flag to true.
            mIsConnectedService = true;
        } else {
            // End the activity and let the user know something went wrong.
            Toast.makeText(this, R.string.tango_cant_initialize, Toast.LENGTH_LONG).show();
            finish();
        }

        // Start the debug text UI update loop.
        mHandler.post(mUpdateUiLoopRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        TangoJNINative.deleteResources();

        // Disconnect from Tango Service, release all the resources that the app is
        // holding from Tango Service.
        if (mIsConnectedService) {
            TangoJNINative.disconnect();
            mIsConnectedService = false;
        }

        // Stop the debug text UI update loop.
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TangoJNINative.destroyActivity();
    }

    /**
     * The "Save ADF" button has been clicked.
     * Defined in {@code activity_area_description.xml}
     */
    public void saveAdfClicked(View view) {
        showSetAdfNameDialog();
    }

    /**
     * Implementation of callback listener interface in SetADFNameDialog.
     */
    @Override
    public void onAdfNameOk(String adfName, String adfUuid) {
        saveAdf(adfName);
    }

    /**
     * Implementation of callback listener interface in SetADFNameDialog.
     */
    @Override
    public void onAdfNameCancelled() {
        // Continue running.
    }

    /**
     * Save the current Area Description File.
     * Performs saving on a background thread and displays a progress dialog.
     */
    private void saveAdf(String adfName) {
        mSaveAdfTask = new SaveAdfTask(this, this, adfName);
        mSaveAdfTask.execute();
    }

    /**
     * Handles failed save from mSaveAdfTask.
     */
    @Override
    public void onSaveAdfFailed(String adfName) {
        String toastMessage = String.format(
                getResources().getString(R.string.save_adf_failed_toast_format),
                adfName);
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        mSaveAdfTask = null;
    }

    /**
     * Handles successful save from mSaveAdfTask.
     */
    @Override
    public void onSaveAdfSuccess(String adfName, String adfUuid) {
        String toastMessage = String.format(
                getResources().getString(R.string.save_adf_success_toast_format),
                adfName, adfUuid);
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        mSaveAdfTask = null;
        finish();
    }

    /**
     * Updates the save progress dialog (called from hello_area_description_app.cc).
     */
    public void updateSavingAdfProgress(int progress) {
        // Note: this method is not called from the UI thread. We read mSaveAdfTask into
        // a local variable because the UI thread may null-out the member variable at any time.
        SaveAdfTask saveAdfTask = mSaveAdfTask;
        if (saveAdfTask != null) {
            saveAdfTask.publishProgress(progress);
        }
    }

    /**
     * Query user's input for the Tango Service configuration.
     */
    private void queryDataFromStartActivity() {
        // Get user's input from the StartActivity.
        Intent initValueIntent = getIntent();
        mIsAreaLearningEnabled =
                initValueIntent.getBooleanExtra(StartActivity.USE_AREA_LEARNING, false);
        mIsLoadingADF =
                initValueIntent.getBooleanExtra(StartActivity.LOAD_ADF, false);
    }

    /**
     * Setup UI components in the current activity.
     */
    private void setupUiComponents() {
        setContentView(R.layout.activity_area_description);

        // Querying screen size, used for computing the normalized touch point.
        Display display = getWindowManager().getDefaultDisplay();
        display.getSize(mScreenSize);

        mAdfUuidTextView = (TextView) findViewById(R.id.adf_uuid_textview);
        mRelocalizationTextView = (TextView) findViewById(R.id.relocalization_textview);

        if (mIsAreaLearningEnabled) {
            // Disable save ADF button until Tango relocalizes to the current ADF.
            findViewById(R.id.save_adf_button).setEnabled(false);
        } else {
            // Hide to save ADF button if leanring mode is off.
            findViewById(R.id.save_adf_button).setVisibility(View.GONE);
        }
    }

    private void showSetAdfNameDialog() {
        Bundle bundle = new Bundle();
        bundle.putString("name", getResources().getString(R.string.default_adf_name));
        bundle.putString("id", ""); // UUID is generated after the ADF is saved.

        FragmentManager manager = getFragmentManager();
        SetAdfNameDialog setAdfNameDialog = new SetAdfNameDialog();
        setAdfNameDialog.setArguments(bundle);
        setAdfNameDialog.show(manager, "AdfNameDialog");
    }

    /**
     * Debug text UI update loop, updating at 10Hz.
     */
    private Runnable mUpdateUiLoopRunnable = new Runnable() {
        public void run() {
            updateUi();
            mHandler.postDelayed(this, UPDATE_UI_INTERVAL_MS);
        }
    };

    /**
     * Update the debug text UI.
     */
    private void updateUi() {
        try {
            mRelocalizationTextView.setText(TangoJNINative.isRelocalized() ?
                    "Relocalized" : "Not Relocalized");

            if (TangoJNINative.isRelocalized()) {
                findViewById(R.id.save_adf_button).setEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception updating UI", e);
        }
    }
}
