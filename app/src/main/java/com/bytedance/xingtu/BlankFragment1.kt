package com.bytedance.xingtu

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [BlankFragment1.newInstance] factory method to
 * create an instance of this fragment.
 */
class BlankFragment1 : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null
    
    private lateinit var viewPager: ViewPager2
    private lateinit var handler: Handler
    private var isAutoScrolling = false
    
    private lateinit var inspirationRecyclerView: RecyclerView

    private val imageList = listOf(
        R.drawable.f1top1,
        R.drawable.f1top2,
        R.drawable.f1top3,
        R.drawable.f1top4,
        R.drawable.f1top5
    )
    
    private val autoScrollRunnable: Runnable = Runnable {
        if (!isAutoScrolling) {
            isAutoScrolling = true
            val currentItem = viewPager.currentItem
            val nextItem = if (currentItem == imageList.size - 1) 0 else currentItem + 1
            viewPager.setCurrentItem(nextItem, true)
            handler.postDelayed({
                isAutoScrolling = false
            }, 500) // 滑动动画时间
        }
        handler.postDelayed(autoScrollRunnable, 2000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
        handler = Handler(Looper.getMainLooper())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_blank1, container, false)
        
        viewPager = view.findViewById(R.id.viewPager)
        val adapter = ImagePagerAdapter(imageList)
        viewPager.adapter = adapter
        startAutoScroll()
        
        // 设置导入图片按钮的点击事件
        val btnOpenAlbum = view.findViewById<Button>(R.id.btnOpenAlbum)
        btnOpenAlbum.setOnClickListener {
            val intent = Intent(requireContext(), AlbumActivity::class.java)
            startActivity(intent)
        }
        
        // 初始化灵感页（混合媒体网格）
        initInspirationRecyclerView(view)
        
        return view
    }
    
    private fun startAutoScroll() {
        // 先移除之前的回调，避免重复注册
        handler.removeCallbacks(autoScrollRunnable)
        handler.postDelayed(autoScrollRunnable, 2000)
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoScrollRunnable)
        isAutoScrolling = false
    }
    
    override fun onResume() {
        super.onResume()
        isAutoScrolling = false
        startAutoScroll()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(autoScrollRunnable)
        
        // 清理播放器资源
        VideoPlayerHolder.pause()
        val player = VideoPlayerHolder.getPlayer()
        if (::inspirationRecyclerView.isInitialized) {
            for (i in 0 until inspirationRecyclerView.childCount) {
                val child = inspirationRecyclerView.getChildAt(i)
                child.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView)?.let { playerView ->
                    if (playerView.player == player) {
                        playerView.player = null
                    }
                }
            }
        }
    }
    
    /**
     * 初始化灵感页 RecyclerView
     */
    private fun initInspirationRecyclerView(view: View) {
        // 初始化播放器
        VideoPlayerHolder.init(requireContext())
        
        // 设置 RecyclerView（横向滑动）
        inspirationRecyclerView = view.findViewById(R.id.inspirationRecyclerView)
        inspirationRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        
        // 创建混合媒体列表（视频 + 图片）
        val mediaItems = createMediaItems()
        android.util.Log.d("BlankFragment1", "Created ${mediaItems.size} media items")
        val adapter = MixedMediaAdapter(mediaItems)
        inspirationRecyclerView.adapter = adapter
        android.util.Log.d("BlankFragment1", "Inspiration adapter set to RecyclerView")
    }
    
    /**
     * 从资源创建混合媒体项列表
     * 包含：video1, video2, picture1, picture2
     */
    private fun createMediaItems(): List<MediaContentItem> {
        val resources = requireContext().resources
        val packageName = requireContext().packageName

        val mediaItems = mutableListOf<MediaContentItem>()
        
        // 添加视频：video1, video2
        for (i in 1..2) {
            val resourceName = "video$i"
            val resourceId = resources.getIdentifier(resourceName, "raw", packageName)
            
            if (resourceId != 0) {
                val uri = Uri.parse("android.resource://$packageName/$resourceId")
                android.util.Log.d("BlankFragment1", "Created video item $i: $resourceName, resourceId=$resourceId, uri=$uri")
                mediaItems.add(
                    MediaContentItem.Video(
                        id = i.toLong(),
                        uri = uri,
                        displayName = "Video $i",
                        resourceName = resourceName
                    )
                )
            } else {
                android.util.Log.w("BlankFragment1", "Resource not found: $resourceName")
            }
        }
        
        // 添加图片：picture1, picture2
        for (i in 1..2) {
            val resourceName = "picture$i"
            val resourceId = resources.getIdentifier(resourceName, "raw", packageName)
            
            if (resourceId != 0) {
                val uri = Uri.parse("android.resource://$packageName/$resourceId")
                android.util.Log.d("BlankFragment1", "Created image item $i: $resourceName, resourceId=$resourceId, uri=$uri")
                mediaItems.add(
                    MediaContentItem.Image(
                        id = (i + 2).toLong(),
                        uri = uri,
                        displayName = "Picture $i",
                        resourceName = resourceName
                    )
                )
            } else {
                android.util.Log.w("BlankFragment1", "Resource not found: $resourceName")
            }
        }

        return mediaItems
    }
    
    override fun onStop() {
        super.onStop()
        VideoPlayerHolder.pause()
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment BlankFragment1.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            BlankFragment1().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}