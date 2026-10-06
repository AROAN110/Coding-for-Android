package com.AROAN110.CodingAndroid.env

/** 用户在安装过程中按下 Ctrl+C，安装被强制打断。 */
class InstallationInterruptedException(message: String = "安装已被用户中断") : Exception(message)

/** 用户在自定义源对话框中点了取消或输入为空，安装主动放弃。 */
class InstallationAbortedException(message: String = "安装已取消") : Exception(message)