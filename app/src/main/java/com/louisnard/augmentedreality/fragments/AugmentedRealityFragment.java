package com.louisnard.augmentedreality.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.louisnard.augmentedreality.BuildConfig;
import com.louisnard.augmentedreality.DevUtils;
import com.louisnard.augmentedreality.R;
import com.louisnard.augmentedreality.mock.MockPoint;
import com.louisnard.augmentedreality.model.database.DbContract;
import com.louisnard.augmentedreality.model.database.DbHelper;
import com.louisnard.augmentedreality.model.objects.Point;
import com.louisnard.augmentedreality.model.services.Compass;
import com.louisnard.augmentedreality.model.services.PointService;
import com.louisnard.augmentedreality.views.CompassView;
import com.louisnard.augmentedreality.views.PointsView;

import java.util.List;

/**
 * Fragment showing the points around the user location using augmented reality over a camera preview.<br>
 *
 * @author Alexandre Louisnard
 */

public class AugmentedRealityFragment extends Fragment implements LocationListener, Compass.CompassListener {

    // Tag
    private static final String TAG = AugmentedRealityFragment.class.getSimpleName();
    private static final String TAG_ALERT_DIALOG_ENABLE_GPS = AlertDialogFragment.TAG + "ENABLE_GPS";

    // Constants
    // The minimum distance the user must have moved from its previous location to recalculate azimuths and distances, in meters
    private static final int MIN_DISTANCE_DIFFERENCE_BETWEEN_RECALCULATIONS = 10;
    // The minimum distance the user must have moved from its previous location to reload the points from the database, in meters
    private static final int MIN_DISTANCE_DIFFERENCE_BETWEEN_DATABASE_RELOADS = 500;
    // The maximum distance to search and display points around the user's location, in meters
    private static final int MAX_RADIUS_DISTANCE_TO_SEARCH_POINTS_AROUND = 10000;
    // The minimum time interval between GPS location updates, in milliseconds
    private static final long MIN_TIME_INTERVAL_BETWEEN_LOCATION_UPDATES = 5000;
    // The maximum age of a location update from the system to be considered as still valid (in order to avoid working with old positions), in milliseconds
    private static final long MAX_AGE_FOR_A_LOCATION = 3 * 60000;
    // The minimum difference with the last orientation values from Compass for the CompassListener to be notified, in degrees
    private static final float MIN_AZIMUTH_DIFFERENCE_BETWEEN_COMPASS_UPDATES = 1;
    private static final float MIN_VERTICAL_INCLINATION_DIFFERENCE_BETWEEN_COMPASS_UPDATES = 1;
    private static final float MIN_HORIZONTAL_INCLINATION_DIFFERENCE_BETWEEN_COMPASS_UPDATES = 1;

    // Location
    private LocationManager mLocationManager;
    private Location mLastGpsLocation;

    // Compass
    private Compass mCompass;

    // Points
    private Point mUserLocationPoint;
    private Location mUserLocationAtLastDbReading;
    private List<Point> mPoints;

    // Views
    private CompassView mCompassView;
    private PointsView mPointsView;
    private TextView mGpsStatusTextView;
    private TextView mVerticalInclinationTextView;
    private TextView mHorizontalInclinationTextView;

    // Request codes
    private final int REQUEST_PERMISSIONS = 1;
    private final int REQUEST_ENABLE_GPS = 2;

    // Check for regular GPS updates
    // Init
    private Handler mCheckGpsHandler = new Handler();
    private Runnable mCheckGpsRunnable = new Runnable() {
        @Override
        public void run() {
            updateGpsStatus();
            mCheckGpsHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check permissions
        if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Missing permissions.");
            return;
        }

        // Location
        mLocationManager = (LocationManager) getActivity().getSystemService(Activity.LOCATION_SERVICE);
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_INTERVAL_BETWEEN_LOCATION_UPDATES, 5, this);

        // Compass
        mCompass = Compass.newInstance(getContext(), this);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    //@Override
    protected int getTextureViewResIdForCameraPreview() {
        return R.id.texture_view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Views
        mCompassView = (CompassView) view.findViewById(R.id.compass_view);
        mPointsView = (PointsView) view.findViewById(R.id.points_view);
        mGpsStatusTextView = (TextView) view.findViewById(R.id.gps_status_text_view);
        mVerticalInclinationTextView = (TextView) view.findViewById(R.id.vertical_inclination_text_view);
        mHorizontalInclinationTextView = (TextView) view.findViewById(R.id.horizontal_inclination_text_view);

        // Check GPS status
        updateGpsStatus();

        // Set camera angles
        /*float[] cameraAnglesOfView = getCameraAnglesOfView(getBackCameraId());
        if (cameraAnglesOfView != null) {
            mPointsView.setCameraAngles(cameraAnglesOfView[0], cameraAnglesOfView[1]);
        }*/
    }

    @Override
    public void onResume() {
        super.onResume();

        // Dump database for debug use only
        if (BuildConfig.DEBUG) {
            DevUtils.exportDatabaseToExternalStorage(getActivity(), DbHelper.getDbName());
        }

        // Start compass
        if (mCompass != null) mCompass.start(MIN_AZIMUTH_DIFFERENCE_BETWEEN_COMPASS_UPDATES, MIN_VERTICAL_INCLINATION_DIFFERENCE_BETWEEN_COMPASS_UPDATES, MIN_HORIZONTAL_INCLINATION_DIFFERENCE_BETWEEN_COMPASS_UPDATES);

        // Start GPS updated checks
        mCheckGpsHandler.postDelayed(mCheckGpsRunnable, 1000);

        // TODO: for test use only: populate database
        final DbHelper dbHelper = DbHelper.getInstance(getActivity().getApplicationContext());
        dbHelper.clearTable(DbContract.PointsColumns.TABLE_NAME);
        dbHelper.addPoints(MockPoint.getPoints());
    }

    @Override
    public void onPause() {
        // Stop GPS updated checks
        mCheckGpsHandler.removeCallbacks(mCheckGpsRunnable);

        super.onPause();

        // Stop compass
        if (mCompass != null) mCompass.stop();
    }

    // CompassListener interface
    @Override
    public void onOrientationChanged(float azimuth, float verticalInclination, float horizontalInclination) {
        mCompassView.updateAzimuth(azimuth);
        mVerticalInclinationTextView.setText(String.format(getString(R.string.vertical_inclination), verticalInclination));
        mHorizontalInclinationTextView.setText(String.format(getString(R.string.horizontal_inclination), horizontalInclination));
        mPointsView.updateOrientation(azimuth, verticalInclination, horizontalInclination);
    }

    // LocationListener interface
    @Override
    public void onLocationChanged(Location location) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "LocationListener.onLocationChanged(): " + location.toString());
            if (location.isFromMockProvider()) {
                Log.d(TAG, "Location received is from mock provider");
            }
        }

        // Check the location validity
        if (location.getTime() >= System.currentTimeMillis() - MAX_AGE_FOR_A_LOCATION) {
            mLastGpsLocation = location;

            // Load points around the user from the database
            if (mPoints == null || mUserLocationAtLastDbReading == null || mUserLocationAtLastDbReading.distanceTo(location) > MIN_DISTANCE_DIFFERENCE_BETWEEN_DATABASE_RELOADS) {
                mUserLocationAtLastDbReading = location;
                final DbHelper dbHelper = DbHelper.getInstance(getActivity().getApplicationContext());
                mPoints = dbHelper.getPointsAround(location, MAX_RADIUS_DISTANCE_TO_SEARCH_POINTS_AROUND);
                if (BuildConfig.DEBUG) Log.d(TAG, "Found " + mPoints.size() + " points in the database around the new user location");
            }

            // Update user location and recalculate relative azimuths of points from the new user location
            if (mUserLocationPoint == null || mUserLocationPoint.distanceTo(location) > MIN_DISTANCE_DIFFERENCE_BETWEEN_RECALCULATIONS) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Recalculating points azimuth from the new user location");
                mUserLocationPoint = new Point(getString(R.string.your_location), location);
                // Update points view
                mPointsView.setPoints(mUserLocationPoint, PointService.sortPointsByRelativeAzimuth(mUserLocationPoint, mPoints));
            }
        }
        updateGpsStatus();
    }

    // LocationListener interface
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (BuildConfig.DEBUG) Log.d(TAG, "LocationListener.onStatusChanged()");
        updateGpsStatus();
    }

    // LocationListener interface
    @Override
    public void onProviderEnabled(String provider) {
        if (BuildConfig.DEBUG) Log.d(TAG, "LocationListener.onProviderEnabled()");
        updateGpsStatus();
    }

    // LocationListener interface
    @Override
    public void onProviderDisabled(String provider) {
        if (BuildConfig.DEBUG) Log.d(TAG, "LocationListener.onProviderDisabled()");
        updateGpsStatus();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_ENABLE_GPS:
                if (resultCode == Activity.RESULT_OK) {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getActivity().recreate();
            } else {
                getActivity().recreate();
            }
        }
    }

    // Check GPS status and update UI accordingly
    // Should be called whenever GPS status has changed
    private void updateGpsStatus() {
        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "GPS is disabled");
            mLastGpsLocation = null;
            mGpsStatusTextView.setText(getString(R.string.gps_disabled));
            mPointsView.setPoints(null, null);
            showEnableGpsAlertDialog();
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "GPS is enabled");
            dismissEnableGpsAlertDialog();
            if (mLastGpsLocation != null && mLastGpsLocation.getTime() >= System.currentTimeMillis() - MAX_AGE_FOR_A_LOCATION) {
                if (BuildConfig.DEBUG) Log.d(TAG, "GPS located");
                mGpsStatusTextView.setText(String.format(getString(R.string.gps_located), (System.currentTimeMillis() - mLastGpsLocation.getTime()) / 1000));
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "GPS waiting for location");
                mGpsStatusTextView.setText(getString(R.string.gps_waiting_for_location));
                mPointsView.setPoints(null, null);
            }
        }
    }

    // Display an alert dialog asking the user to enable the GPS
    private void showEnableGpsAlertDialog() {
        if (getFragmentManager().findFragmentByTag(TAG_ALERT_DIALOG_ENABLE_GPS) == null) {
            final AlertDialogFragment alertDialogFragment = AlertDialogFragment.newInstance(R.string.gps, R.string.gps_disabled_alert_dialog_message, android.R.string.ok, android.R.string.cancel);
            alertDialogFragment.setTargetFragment(this, REQUEST_ENABLE_GPS);
            final FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
            fragmentTransaction.add(alertDialogFragment, TAG_ALERT_DIALOG_ENABLE_GPS);
            fragmentTransaction.commitAllowingStateLoss();
        }
    }

    // Dismiss the alert dialog asking the user to enable the GPS
    private void dismissEnableGpsAlertDialog() {
        if (getFragmentManager().findFragmentByTag(TAG_ALERT_DIALOG_ENABLE_GPS) != null) {
            ((AlertDialogFragment) getFragmentManager().findFragmentByTag(TAG_ALERT_DIALOG_ENABLE_GPS)).dismissAllowingStateLoss();
        }
    }
}
