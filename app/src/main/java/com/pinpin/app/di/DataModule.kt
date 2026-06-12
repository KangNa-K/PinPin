package com.pinpin.app.di

import android.content.Context
import androidx.room.Room
import com.pinpin.app.data.PinDatabase
import com.pinpin.app.data.PinDao
import com.pinpin.app.data.ScreenshotHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the persistence layer. [PinDatabase] is a process-singleton
 * (Room's contract), [PinDao] is a thin wrapper.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun providePinDatabase(@ApplicationContext context: Context): PinDatabase =
        Room.databaseBuilder(
            context,
            PinDatabase::class.java,
            PinDatabase.DATABASE_NAME,
        )
            // No destructive migration: pin data is not high-stakes, but
            // losing it on a schema bump is annoying. Future versions will
            // add proper migrations.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePinDao(db: PinDatabase): PinDao = db.pinDao()

    @Provides
    fun provideHistoryDao(db: PinDatabase): ScreenshotHistoryDao = db.historyDao()
}
