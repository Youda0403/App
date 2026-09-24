package com.pairplay.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.pairplay.app.R
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.PairPlayDatabase
import com.pairplay.app.overlay.OverlayService
import com.pairplay.app.overlay.OverlayVisibility
import com.pairplay.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 홈 화면 위젯.
 *
 * 설계서대로 **계속 움직이지 않는다.** 두 캐릭터와 이름, 지금 어떤 상황극이
 * 도는지만 가끔 갱신해 보여 준다. 탭하면 앱이 열린다.
 * 오버레이와 같은 데이터베이스를 읽으므로 설정이 따로 놀지 않는다.
 */
class PairPlayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderAll(context, appWidgetManager, appWidgetIds)
            } catch (e: Exception) {
                Log.w(TAG, "위젯을 그리지 못했습니다", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PairPlayWidget"

        /** 위젯에 넣을 이미지의 최대 크기(px). 너무 크면 시스템이 거부한다. */
        private const val MAX_IMAGE_PX = 220

        /** 숨어 있을 때 위젯 캐릭터를 그리는 진하기(0~255). */
        private const val HIDDEN_ALPHA = 80

        /** 너무 자주 갱신하면 배터리만 먹는다. 최소 이 간격은 둔다. */
        private const val MIN_REFRESH_INTERVAL_MS = 20_000L

        private var lastRefreshAt = 0L

        /**
         * 위젯을 지금 다시 그린다.
         * 상황극이 바뀔 때마다 불리므로 너무 잦은 갱신은 걸러 낸다.
         */
        fun refresh(context: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return

            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PairPlayWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return

            lastRefreshAt = now
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    renderAll(context.applicationContext, manager, ids)
                } catch (e: Exception) {
                    Log.w(TAG, "위젯을 갱신하지 못했습니다", e)
                }
            }
        }

        private suspend fun renderAll(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            val database = PairPlayDatabase.get(context)
            val characters = database.characterDao().getAll()
            val pair = database.pairDao().getActive()

            val byId = characters.associateBy { it.id }
            val a = pair?.let { byId[it.characterAId] } ?: characters.firstOrNull()
            val b = pair?.characterBId?.let { byId[it] }
                ?: characters.getOrNull(1).takeIf { pair == null }

            val views = buildViews(context, a, b)
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, views) }
                    .onFailure { Log.w(TAG, "위젯 $id 갱신 실패", it) }
            }
        }

        private fun buildViews(
            context: Context,
            a: CharacterEntity?,
            b: CharacterEntity?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_pairplay)

            // 숨어 있으면 위젯의 캐릭터도 흐릿하게 그려 '지금 숨어 있다' 는 걸 보여 준다.
            val hidden = OverlayService.isRunning.value &&
                OverlayService.visibility.value != OverlayVisibility.SHOWN

            bindCharacter(context, views, a, R.id.widget_image_a, R.id.widget_name_a, hidden)
            bindCharacter(context, views, b, R.id.widget_image_b, R.id.widget_name_b, hidden)

            views.setTextViewText(R.id.widget_status, statusText(context, a))

            // 탭하면 앱이 열린다.
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            return views
        }

        private fun statusText(context: Context, a: CharacterEntity?): String = when {
            a == null -> context.getString(R.string.widget_no_character)
            !OverlayService.isRunning.value -> context.getString(R.string.widget_stopped)
            OverlayService.visibility.value == OverlayVisibility.HIDDEN_BY_APP ->
                context.getString(R.string.widget_hidden_by_app)

            OverlayService.visibility.value == OverlayVisibility.HIDDEN_BY_USER ->
                context.getString(R.string.widget_hidden_by_user)

            else -> OverlayService.currentScene.value
                ?: context.getString(R.string.widget_idle)
        }

        private fun bindCharacter(
            context: Context,
            views: RemoteViews,
            character: CharacterEntity?,
            imageId: Int,
            nameId: Int,
            hidden: Boolean
        ) {
            if (character == null) {
                views.setViewVisibility(imageId, View.GONE)
                views.setViewVisibility(nameId, View.GONE)
                return
            }
            views.setViewVisibility(imageId, View.VISIBLE)
            views.setViewVisibility(nameId, View.VISIBLE)
            views.setTextViewText(nameId, character.name)

            val loaded = loadThumbnail(character.imagePath)
            val bitmap = if (hidden && loaded != null) faded(loaded) else loaded
            if (bitmap != null) {
                views.setImageViewBitmap(imageId, bitmap)
            } else {
                // 이미지를 못 읽어도 위젯 전체가 비지 않도록 기본 아이콘을 쓴다.
                views.setImageViewResource(imageId, R.drawable.ic_launcher_foreground)
            }
        }

        /**
         * 흐릿하게 만든 그림. 위젯에서 '숨어 있음' 을 나타낸다.
         *
         * 위젯 쪽 뷰의 투명도를 바꾸는 방법도 있지만, 위젯은 쓸 수 있는 기능이 정해져 있어
         * 잘못 부르면 위젯 전체가 안 그려진다(검은 상자). 그래서 그림 자체를 흐리게 만든다.
         */
        private fun faded(source: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = HIDDEN_ALPHA }
            Canvas(out).drawBitmap(source, 0f, 0f, paint)
            source.recycle()
            return out
        }

        /**
         * 위젯에 넣을 만큼 작게 줄여서 읽는다.
         * 원본 그대로 넣으면 시스템이 크기 제한으로 거부해 위젯이 비어 버린다.
         */
        private fun loadThumbnail(path: String): Bitmap? = try {
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_PX ||
                    bounds.outHeight / (sample * 2) >= MAX_IMAGE_PX
                ) {
                    sample *= 2
                }

                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "위젯 이미지를 담을 메모리가 부족합니다", e)
            null
        }
    }
}
