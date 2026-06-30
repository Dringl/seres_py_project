package com.seres.evtoldemo.di

import android.content.Context
import androidx.room.Room
import com.seres.evtoldemo.BuildConfig
import com.seres.evtoldemo.data.auth.AuthInterceptor
import com.seres.evtoldemo.data.local.AppDatabase
import com.seres.evtoldemo.data.local.OrderDao
import com.seres.evtoldemo.data.remote.AuthApi
import com.seres.evtoldemo.data.remote.DispatchApi
import com.seres.evtoldemo.data.repository.DispatchRepository
import com.seres.evtoldemo.data.repository.FakeDispatchRepository
import com.seres.evtoldemo.location.LocationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.seres.evtoldemo.data.repository.RemoteDispatchRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideDispatchApi(retrofit: Retrofit): DispatchApi = retrofit.create(DispatchApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "evtol-demo.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideOrderDao(database: AppDatabase): OrderDao = database.orderDao()

    @Provides
    @Singleton
    fun provideDispatchRepository(
        api: DispatchApi,
        orderDao: OrderDao
    ): DispatchRepository =
        if (BuildConfig.USE_FAKE_REPOSITORY) FakeDispatchRepository(orderDao)
        else RemoteDispatchRepository(api, orderDao)

    @Provides
    @Singleton
    fun provideLocationTracker(@ApplicationContext context: Context): LocationTracker {
        return LocationTracker(context)
    }
}
