package tachiyomi.data

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(context: Context): SqlDriver {
        return AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "tachiyomi.db"),
            schema = Database.Schema,
            // RK --> one value, so ForeignKeyEnforcementTest pins the configuration production opens with
            configuration = sqlDriverConfiguration,
            // RK <--
        )
    }

    // RK -->
    internal val sqlDriverConfiguration = AndroidxSqliteConfiguration(
        isForeignKeyConstraintsEnabled = true,
    )
    // RK <--

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(
                read_atAdapter = DateColumnAdapter,
            ),
            mangaAdapter = Manga.Adapter(
                remote_genreAdapter = StringListColumnAdapter,
                remote_update_strategyAdapter = UpdateStrategyColumnAdapter,
                remote_memoAdapter = MemoColumnAdapter,
            ),
            chapterAdapter = Chapter.Adapter(
                remote_memoAdapter = MemoColumnAdapter,
            ),
            // RK --> light-novel vertical
            novelsAdapter = Novels.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
            // RK: manga custom-info overlay
            custom_manga_infoAdapter = Custom_manga_info.Adapter(
                genreAdapter = StringListColumnAdapter,
            ),
            // RK: novel custom-info overlay
            custom_novel_infoAdapter = Custom_novel_info.Adapter(
                genreAdapter = StringListColumnAdapter,
            ),
            // RK <--
        )
    }
}
