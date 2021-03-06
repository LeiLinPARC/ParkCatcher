/*
    Park Catcher Montréal
    Find a free parking in the nearest residential street when driving in
    Montréal. A Montréal Open Data project.

    Copyright (C) 2012 Mudar Noufal <mn@mudar.ca>

    This file is part of Park Catcher Montréal.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ca.mudar.parkcatcher.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.DrawerLayout;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import ca.mudar.parkcatcher.Const;
import ca.mudar.parkcatcher.ParkingApp;
import ca.mudar.parkcatcher.R;
import ca.mudar.parkcatcher.service.SyncService;
import ca.mudar.parkcatcher.ui.activities.base.NavdrawerActivity;
import ca.mudar.parkcatcher.ui.fragments.MainMapFragment;
import ca.mudar.parkcatcher.ui.fragments.MapErrorFragment;
import ca.mudar.parkcatcher.ui.views.RefreshProgressLayout;
import ca.mudar.parkcatcher.ui.views.SlidingUpCalendar;
import ca.mudar.parkcatcher.utils.ConnectionHelper;
import ca.mudar.parkcatcher.utils.EulaHelper;
import ca.mudar.parkcatcher.utils.LocationHelper;
import ca.mudar.parkcatcher.utils.ParkingTimeHelper;

public class MainActivity extends NavdrawerActivity implements
        SlidingUpCalendar.SlidingUpCalendarCallbacks,
        MainMapFragment.MapEventsListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
    private static final String TAG = "MainActivity";
    GoogleApiClient mGoogleApiClient;
    private ParkingApp parkingApp;
    private RefreshProgressLayout mRefreshProgressLayout;
    private SlidingUpCalendar mSlidingUpCalendar;
    private MainMapFragment mMainMapFragment;

    public static Intent newIntent(Context context) {
        final Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        return intent;
    }

    public static Intent newIntent(Context context, int idPost, double geoLat, double geoLng) {
        final Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        final Bundle extras = new Bundle();
        extras.putInt(Const.BundleExtras.ID_POST, idPost);
        extras.putDouble(Const.BundleExtras.GEO_LAT, geoLat);
        extras.putDouble(Const.BundleExtras.GEO_LNG, geoLng);
        intent.putExtras(extras);

        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.activity_map);
        parkingApp = (ParkingApp) getApplicationContext();

        final int startupStatus = verifyStartupStatus();
        downloadDataIfNeeded(startupStatus);

        setContentView(R.layout.activity_main_map);
        getActionBarToolbar();

        // Initialize views
        mSlidingUpCalendar = (SlidingUpCalendar) findViewById(R.id.sliding_layout);
        mRefreshProgressLayout = (RefreshProgressLayout) findViewById(R.id.swipe_refresh);

        if (startupStatus != Const.StartupStatus.OK) {
            /**
             * Special error case when EULA not accepted and internet connection is unavailable.
             */
            hideSlidingUpCalendar();

            final Fragment fragment = MapErrorFragment.newInstance(startupStatus);
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.content_frame, fragment, Const.FragmentTags.ERROR)
                    .commit();
            // Stop here!
            return;
        }

        connectGoogleApiClient();

        if (savedInstanceState == null) {
            mMainMapFragment = new MainMapFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.content_frame, mMainMapFragment, Const.FragmentTags.MAP)
                    .commit();
        } else {
            mMainMapFragment = (MainMapFragment) getSupportFragmentManager()
                    .findFragmentByTag(Const.FragmentTags.MAP);
        }

        // Enable interaction between the Map and sliding-up Calendar filter
        final Fragment calendarFilterFragment = getSupportFragmentManager()
                .findFragmentByTag(Const.FragmentTags.SLIDING_UP_CALENDAR);
        calendarFilterFragment.setTargetFragment(mMainMapFragment, Const.RequestCodes.MAP);

        handleIntent(getIntent());
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!ConnectionHelper.hasConnection(this)) {
            ConnectionHelper.showDialogNoConnection(this);
        }

        // Update calendar to handle new values defined in Favorites
        mSlidingUpCalendar.updateParkingCalendar();
    }

    @Override
    public void onStop() {
        super.onStop();

        disconnectGoogleApiClient();
    }

    @Override
    public void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == Const.RequestCodes.EULA) {
            if (!EulaHelper.acceptEula(resultCode, this)) {
                this.finish();
            }
        } else if (requestCode == Const.RequestCodes.PLAYSERVICES) {
            handlePlayservicesResult();
        }
    }

    @Override
    protected void onNavdrawerStateChanged(int newState) {
        if (DrawerLayout.STATE_DRAGGING == newState || DrawerLayout.STATE_SETTLING == newState) {
            collapseSlidingUpCalendar();
        }
    }

    /**
     * Toggle searchView for Search physical button
     *
     * @param keyCode
     * @param event
     * @return
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleMapSearchView(false);
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            toggleMapSearchView(true);
        }

        return super.onKeyUp(keyCode, event);
    }

    /**
     * GoogleApiClient implementation
     *
     * @param bundle
     */
    @Override
    public void onConnected(Bundle bundle) {
        final Location lastKnownLocation = LocationServices.FusedLocationApi
                .getLastLocation(mGoogleApiClient);

        parkingApp.setLocation(lastKnownLocation);
        disconnectGoogleApiClient();
    }

    /**
     * GoogleApiClient implementation
     *
     * @param i
     */
    @Override
    public void onConnectionSuspended(int i) {
    }

    /**
     * GoogleApiClient implementation
     *
     * @param connectionResult
     */
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
    }

    /**
     * BaseActivity implementation
     *
     * @return
     */
    @Override
    protected int getSelfNavDrawerItem() {
        return Const.NavdrawerSection.MAP;
    }

    /**
     * NavDrawer special activity, not cleared from backstack
     *
     * @return
     */
    @Override
    protected boolean isSpecialActivity() {
        return true;
    }

    /**
     * MainMapFragment implementation
     */
    @Override
    public void onMapSearchClick() {
        collapseSlidingUpCalendar();
    }

    /**
     * MainMapFragment implementation
     */
    @Override
    public void onMapDataProcessing(boolean isProcessing) {
        toggleProgressBar(isProcessing);
    }

    private void toggleProgressBar(boolean isLoading) {
        if (mRefreshProgressLayout != null) {
            mRefreshProgressLayout.setRefreshing(isLoading);
            if (!Const.SUPPORTS_ICS) {
                mRefreshProgressLayout.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            }
        }
    }

    /**
     * Implements SlidingUpCalendarCallbacks
     */
    @Override
    public void showSlidingUpCalendar() {
        if (mSlidingUpCalendar != null) {
            mSlidingUpCalendar.hidePanel();
        }
    }

    /**
     * Implements SlidingUpCalendarCallbacks
     */
    @Override
    public void hideSlidingUpCalendar() {
        if (mSlidingUpCalendar != null) {
            mSlidingUpCalendar.hidePanel();
        }
    }

    /**
     * Implements SlidingUpCalendarCallbacks
     */
    @Override
    public void collapseSlidingUpCalendar() {
        if (mSlidingUpCalendar != null) {
            mSlidingUpCalendar.collapsePanel();
        }
    }

    private void toggleMapSearchView(boolean expanded) {
        if (mMainMapFragment != null) {
            mMainMapFragment.toggleSearchView(expanded);
        }
    }

    /**
     * Verify EULA and Google Play Services availability.
     * Database and Map data are loaded on startup while showing EULA.
     *
     * @return int Status code.
     */
    private int verifyStartupStatus() {
        if (!EulaHelper.hasAcceptedEula(this)) {
            if (ConnectionHelper.hasConnection(this)) {
                EulaHelper.showEula(false, this);
            } else {
                return Const.StartupStatus.ERROR_CONNECTION;
            }
        }

        // If EULA can be displayed with an internet connection, continue to check PlayServices status
        if (GooglePlayServicesUtil
                .isGooglePlayServicesAvailable(getApplicationContext()) != ConnectionResult.SUCCESS) {
            return Const.StartupStatus.ERROR_PLAYSERVICES;
        }

        return Const.StartupStatus.OK;
    }

    private void handlePlayservicesResult() {
        if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(getApplicationContext()) == ConnectionResult.SUCCESS) {
            // Playservice updated, display message and restart activity
            parkingApp.showToastText(R.string.toast_playservices_restart, Toast.LENGTH_LONG);
            final Intent intent = getIntent();
            this.finish();
            startActivity(intent);
        }
    }

    /**
     * Download the remote Database
     */
    private void downloadDataIfNeeded(int startupStatus) {
        if (startupStatus != Const.StartupStatus.ERROR_CONNECTION
                && !parkingApp.hasLoadedData()) {
            // The service runs in the background with no listener
            final Intent intent = new Intent(Intent.ACTION_SYNC, null, getApplicationContext(),
                    SyncService.class);
            intent.putExtra(Const.BundleExtras.SERVICE_LOCAL, false);
            intent.putExtra(Const.BundleExtras.SERVICE_REMOTE, true);
            startService(intent);
        }
    }

    private synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    private void connectGoogleApiClient() {
        if (mGoogleApiClient == null) {
            buildGoogleApiClient();
        }
        if (mGoogleApiClient != null && !(mGoogleApiClient.isConnected() || mGoogleApiClient.isConnecting())) {
            mGoogleApiClient.connect();
        }
    }

    private void disconnectGoogleApiClient() {
        if (mGoogleApiClient != null && (mGoogleApiClient.isConnected() || mGoogleApiClient.isConnecting())) {
            mGoogleApiClient.disconnect();
        }
    }

    private void setInitialMapCenter(Location location) {
        if (mMainMapFragment != null && location != null) {
            mMainMapFragment.setInitialMapCenter(location);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        setInitialMapCenter(LocationHelper.getLocationFromIntent(intent));
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            updateParkingTimeFromUri(intent.getData());
        }
    }

    private void updateParkingTimeFromUri(Uri uri) {
        List<String> pathSegments = uri.getPathSegments();

        // http://www.capteurdestationnement.com/map/search/2/15.5/12
        // http://www.capteurdestationnement.com/map/search/2/15.5/12/h2w2e7

        if ((pathSegments.size() >= 5)
                && (pathSegments.get(0).equals(Const.BundleExtras.URL_PATH_MAP))
                && (pathSegments.get(1).equals(Const.BundleExtras.URL_PATH_SEARCH))) {

            try {
                final int dayOfWeekIso = Integer.valueOf(pathSegments.get(2));
                final double clockTime = Double.valueOf(pathSegments.get(3));
                final int duration = Integer.valueOf(pathSegments.get(4));

                final int hourOfDay = ParkingTimeHelper.getHoursFromClockTime(clockTime);
                final int minute = ParkingTimeHelper.getMintuesFromClockTime(clockTime);

                GregorianCalendar calendar = new GregorianCalendar();

                calendar.set(Calendar.DAY_OF_WEEK, ParkingTimeHelper.getDayOfWeek(dayOfWeekIso));
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                calendar.set(Calendar.MINUTE, minute);

                parkingApp.setParkingCalendar(calendar);
                parkingApp.setParkingDuration(duration);
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
    }

}
