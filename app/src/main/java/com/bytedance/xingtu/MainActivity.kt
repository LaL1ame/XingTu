package com.bytedance.xingtu

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity(), View.OnClickListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        

        val mainLayout = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        val frameLayout = findViewById<View>(R.id.framelayout)
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { v, insets ->
            insets
        }

        //按下会自动触发onclick函数
        val button1=findViewById<View>(R.id.xiutu)
        button1.setOnClickListener(this)
//        val button2=findViewById<View>(R.id.linggan)
//        button2.setOnClickListener(this)
        val button3=findViewById<View>(R.id.wode)
        button3.setOnClickListener(this)

        // 默认加载 BlankFragment1
        if (savedInstanceState == null) {
            replaceFragment(BlankFragment1(), false)  // 初始加载不加入返回栈
        }
        
        // 处理返回键
        setupBackPressHandler()
    }
    
    /**
     * 设置返回键处理逻辑
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.framelayout)
                
                when (currentFragment) {
                    is BlankFragment3 -> {
                        // 在"我的"页面，返回到"修图"页
                        replaceFragment(BlankFragment1(), false)
                    }
                    is BlankFragment1 -> {
                        // 在"修图"页，退出应用
                        finish()
                    }
                    else -> {
                        // 其他情况，使用默认返回行为
                        if (supportFragmentManager.backStackEntryCount > 0) {
                            supportFragmentManager.popBackStack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
    }



    override fun onClick(v: View) {
        when(v.id){
            R.id.xiutu->
                replaceFragment(BlankFragment1(), false)  // 切换到修图页，不加入返回栈
//            R.id.linggan->
//                replaceFragment(BlankFragment2(), true)
            R.id.wode->
                replaceFragment(BlankFragment3(), true)  // 切换到我的页，加入返回栈
        }
    }
    
    /**
     * 替换Fragment
     * @param fragment 要显示的Fragment
     * @param addToBackStack 是否加入返回栈
     */
    private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean) {
        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()
        transaction.replace(R.id.framelayout, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

}