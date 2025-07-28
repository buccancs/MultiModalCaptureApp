package com.multimodal.capture.di

import android.content.Context
import com.multimodal.capture.data.interfaces.IDataSource
import com.multimodal.capture.data.managers.GSRSensorManager
import com.multimodal.capture.data.managers.ThermalCameraManager
import com.multimodal.capture.data.network.NetworkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing hardware manager dependencies.
 * Provides singleton instances of data sources for dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    @Provides
    @Singleton
    fun provideNetworkManager(@ApplicationContext context: Context): NetworkManager {
        return NetworkManager(context)
    }

    @Provides
    @Singleton
    fun provideGSRSensorManager(
        @ApplicationContext context: Context,
        networkManager: NetworkManager
    ): GSRSensorManager {
        return GSRSensorManager(context, networkManager)
    }

    @Provides
    @Singleton
    fun provideThermalCameraManager(
        @ApplicationContext context: Context,
        networkManager: NetworkManager
    ): ThermalCameraManager {
        return ThermalCameraManager(context, networkManager)
    }
}