package com.mapzen.android.lost.internal;

import com.mapzen.android.lost.api.FusedLocationProviderApi;
import com.mapzen.android.lost.api.LocationAvailability;
import com.mapzen.android.lost.api.LocationCallback;
import com.mapzen.android.lost.api.LocationListener;
import com.mapzen.android.lost.api.LocationRequest;
import com.mapzen.android.lost.api.LocationResult;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of the {@link FusedLocationProviderApi}.
 */
public class FusedLocationProviderApiImpl
    implements FusedLocationProviderApi, LocationEngine.Callback {

  private static final String TAG = FusedLocationProviderApiImpl.class.getSimpleName();

  private final Context context;
  private boolean mockMode;
  private LocationEngine locationEngine;
  private Map<LocationListener, LocationRequest> listenerToRequest;
  private Map<PendingIntent, LocationRequest> intentToRequest;
  private Map<LocationCallback, LocationRequest> callbackToRequest;
  private Map<LocationCallback, Looper> callbackToLooper;

  public FusedLocationProviderApiImpl(Context context) {
    this.context = context;
    locationEngine = new FusionEngine(context, this);
    listenerToRequest = new HashMap<>();
    intentToRequest = new HashMap<>();
    callbackToRequest = new HashMap<>();
    callbackToLooper = new HashMap<>();
  }

  @Override public Location getLastLocation() {
    return locationEngine.getLastLocation();
  }

  @Override public LocationAvailability getLocationAvailability() {
    return createLocationAvailability();
  }

  @Override public void requestLocationUpdates(LocationRequest request, LocationListener listener) {
    listenerToRequest.put(listener, request);
    locationEngine.setRequest(request);
  }

  @Override public void requestLocationUpdates(LocationRequest request, LocationCallback callback,
      Looper looper) {
    callbackToRequest.put(callback, request);
    callbackToLooper.put(callback, looper);
    locationEngine.setRequest(request);
  }

  @Override public void requestLocationUpdates(LocationRequest request, LocationListener listener,
      Looper looper) {
    throw new RuntimeException("Sorry, not yet implemented");
  }

  @Override
  public void requestLocationUpdates(LocationRequest request, PendingIntent callbackIntent) {
    intentToRequest.put(callbackIntent, request);
    locationEngine.setRequest(request);
  }

  @Override public void removeLocationUpdates(LocationListener listener) {
    listenerToRequest.remove(listener);
    checkAllListenersPendingIntentsAndCallbacks();
  }

  @Override public void removeLocationUpdates(PendingIntent callbackIntent) {
    intentToRequest.remove(callbackIntent);
    checkAllListenersPendingIntentsAndCallbacks();
  }

  @Override public void removeLocationUpdates(LocationCallback callback) {
    callbackToRequest.remove(callback);
    callbackToLooper.remove(callback);
    checkAllListenersPendingIntentsAndCallbacks();
  }

  /**
   * Checks if any listeners or pending intents are still registered for location updates. If not,
   * then shutdown the location engine.
   */
  private void checkAllListenersPendingIntentsAndCallbacks() {
    if (listenerToRequest.isEmpty() && intentToRequest.isEmpty() && callbackToRequest.isEmpty()) {
      locationEngine.setRequest(null);
    }
  }

  @Override public void setMockMode(boolean isMockMode) {
    if (mockMode != isMockMode) {
      toggleMockMode();
    }
  }

  private void toggleMockMode() {
    mockMode = !mockMode;
    locationEngine.setRequest(null);
    if (mockMode) {
      locationEngine = new MockEngine(context, this);
    } else {
      locationEngine = new FusionEngine(context, this);
    }
  }

  @Override public void setMockLocation(Location mockLocation) {
    if (mockMode) {
      ((MockEngine) locationEngine).setLocation(mockLocation);
    }
  }

  @Override public void setMockTrace(File file) {
    if (mockMode) {
      ((MockEngine) locationEngine).setTrace(file);
    }
  }

  @Override public boolean isProviderEnabled(String provider) {
    return locationEngine.isProviderEnabled(provider);
  }

  @Override public void reportLocation(Location location) {
    for (LocationListener listener : listenerToRequest.keySet()) {
      listener.onLocationChanged(location);
    }

    LocationAvailability availability = createLocationAvailability();
    ArrayList<Location> locations = new ArrayList<>();
    locations.add(location);
    final LocationResult result = LocationResult.create(locations);
    for (PendingIntent intent : intentToRequest.keySet()) {
      try {
        Intent toSend = new Intent().putExtra(KEY_LOCATION_CHANGED, location);
        toSend.putExtra(LocationAvailability.EXTRA_LOCATION_AVAILABILITY, availability);
        toSend.putExtra(LocationResult.EXTRA_LOCATION_RESULT, result);
        intent.send(context, 0, toSend);
      } catch (PendingIntent.CanceledException e) {
        Log.e(TAG, "Unable to send pending intent: " + intent);
      }
    }


    for (final LocationCallback callback : callbackToRequest.keySet()) {
      Looper looper = callbackToLooper.get(callback);
      Handler handler = new Handler(looper);
      handler.post(new Runnable() {
        @Override public void run() {
          callback.onLocationResult(result);
        }
      });
    }
  }

  @Override public void reportProviderDisabled(String provider) {
    for (LocationListener listener : listenerToRequest.keySet()) {
      listener.onProviderDisabled(provider);
    }

    notifyLocationAvailabilityChanged();
  }

  @Override public void reportProviderEnabled(String provider) {
    for (LocationListener listener : listenerToRequest.keySet()) {
      listener.onProviderEnabled(provider);
    }

    notifyLocationAvailabilityChanged();
  }

  public void shutdown() {
    listenerToRequest.clear();
    callbackToRequest.clear();
    callbackToLooper.clear();
    locationEngine.setRequest(null);
  }

  public Map<LocationListener, LocationRequest> getListeners() {
    return listenerToRequest;
  }

  private LocationAvailability createLocationAvailability() {
    LocationManager locationManager = (LocationManager) context.getSystemService(
        Context.LOCATION_SERVICE);
    boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    return new LocationAvailability(gpsEnabled || networkEnabled);
  }

  private void notifyLocationAvailabilityChanged() {
    final LocationAvailability availability = createLocationAvailability();
    for (final LocationCallback callback : callbackToRequest.keySet()) {
      Looper looper = callbackToLooper.get(callback);
      Handler handler = new Handler(looper);
      handler.post(new Runnable() {
        @Override public void run() {
          callback.onLocationAvailability(availability);
        }
      });
    }
  }
}
