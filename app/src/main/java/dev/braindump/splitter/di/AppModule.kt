package dev.braindump.splitter.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.braindump.splitter.data.local.BrainDumpDatabase
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.repository.IdeaRepositoryImpl
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope for writes that must finish even after the screen that started them is gone. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BrainDumpDatabase =
        Room.databaseBuilder(context, BrainDumpDatabase::class.java, BrainDumpDatabase.NAME).build()

    @Provides
    fun provideIdeaDao(database: BrainDumpDatabase): IdeaDao = database.ideaDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindIdeaRepository(impl: IdeaRepositoryImpl): IdeaRepository
}
