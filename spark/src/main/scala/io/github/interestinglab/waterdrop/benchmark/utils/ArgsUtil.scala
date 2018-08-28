package io.github.interestinglab.waterdrop.benchmark.utils

object ArgsUtil {

  object ArgsParse {

    def usage() = {
      println(
        """
          |usage:
          |   --help
          |   --key value
        """.stripMargin)
      sys.exit(1)
    }

    def nextOption(list: List[String]): Map[String,String] = {

      list match {
        case Nil => Map()
        case "--app-name"::value::tail => Map("appName"->value.toString) ++ nextOption(tail)
        case "--topic"::value::tail => Map("topic"->value.toString) ++ nextOption(tail)
        case "--target-topic"::value::tail => Map("targetTopic"->value.toString) ++ nextOption(tail)
        case "--group-id"::value::tail => Map("groupId"->value.toString) ++ nextOption(tail)
        case "--window-step"::value::tail => Map("windowStep" -> value.toString)
        case _ => usage()
      }
    }

    def init(args:Array[String]): Map[String,String] = {

      nextOption(args.toList)
    }
  }
}
