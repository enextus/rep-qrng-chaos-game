package org.ThreeDotsSierpinski;

/**
 * Interface for listening to data loading events from RNProvider.
 */
public interface RNLoadListener {

    /** Called when data loading starts. */
    void onLoadingStarted();

    /** Called when data loading completes successfully. */
    void onLoadingCompleted();

    /** Called when an error occurs during data loading. */
    void onError(String errorMessage);

    /** Called when raw data is received from API. */
    void onRawDataReceived(String rawData);

    /**
     * Called when the random number source mode changes.
     * @param mode current mode (QUANTUM or PSEUDO)
     */
    default void onModeChanged(RNProvider.Mode mode) {}

    /**
     * Called when API availability status changes.
     * @param isAvailable true if API is reachable
     */
    default void onApiAvailabilityChanged(boolean isAvailable) {}
}
