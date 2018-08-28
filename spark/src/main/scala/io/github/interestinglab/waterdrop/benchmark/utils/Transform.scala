package io.github.interestinglab.waterdrop.benchmark.utils

import java.text.SimpleDateFormat

object Transform {
  /**
    *
    * @param oldTime dd/MMM/yyyy:HH:mm:ss Z格式化时间
    * @param interval 时间间隔
    * @return 返回经过处理的时间戳,使所有写入es的数据时间戳相隔interval的时间
    */
  def changeTime (oldTime:String): Long = {

    var timestamp:Long =  System.currentTimeMillis()
    timestamp -= timestamp % 1000
    if(oldTime != "localtime"){
      val fm = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val datetime = fm.parse(oldTime)

      timestamp = datetime.getTime
    }
    timestamp
  }
}
