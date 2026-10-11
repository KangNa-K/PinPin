package com.pinpin.app.di

import android.content.Context
import androidx.room.Room
import com.pinpin.app.data.PinDatabase
import com.pinpin.app.data.PinDatabaseMigrations
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
            .addMigrations(
                PinDatabaseMigrations.MIGRATION_1_2,
                PinDatabaseMigrations.MIGRATION_2_3,
                PinDatabaseMigrations.MIGRATION_3_4,
                PinDatabaseMigrations.MIGRATION_4_5,
            )
            .build()

    @Provides
    fun providePinDao(db: PinDatabase): PinDao = db.pinDao()

    @Provides
    fun provideHistoryDao(db: PinDatabase): ScreenshotHistoryDao = db.historyDao()
}
