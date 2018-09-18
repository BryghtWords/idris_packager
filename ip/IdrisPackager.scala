package ip

import ip.eitherops._
import ip.fileops._
import ip.fileops.path._
import ip.ipkgops._
import ip.zipops._
import ip.tupleops._

object IdrisPackager {

  type R[+T] = Either[String, T]
  type RU = EUnit[String]
  val RU: RU = Right(())

  def main(args: Array[String]): Unit = {

    println()

    val argumentStrings =
      args.toList

    val result =
      for {
        arguments <- parseArguments(argumentStrings)
        _         <- arguments match {
                       case Arguments.Create(idrisPath, modulePath, targetPath) =>
                         create(idrisPath, modulePath, targetPath)
                       case Arguments.Idris(idrisPath, idrisModules, idrisArguments) =>
                         runIdris(idrisPath, idrisModules, idrisArguments)
                     }

      } yield {
        ()
      }


    result match {
      case Left(error) => println(error)
      case _ =>
    }
  }

  def runIdris(idrisPath: AbsolutePath, idrisModules: List[AbsolutePath], idrisArguments: List[String]): RU = {

    def findModuleIpkgPath(modulePath: AbsolutePath): Either[String, AbsolutePath] =
      modulePath
        .whenDir{dir =>
           dir.find(raw".*\.ipkg") match {
             case Right(target :: Nil) => Right(modulePath / target)
             case Right(Nil) => Left(s"Provided module path '$modulePath' doesn't containt any ipkg file")
             case Right(_) => Left(s"Provided module path '$modulePath' containts more than one ipkg file and choosing is imposible")
             case Left(cause) => Left(s"Trying to locate an ipkg file at '$modulePath', something went wrong due to:\n$cause")
           }
         }
        .otherwise {_ =>
           Left(s"Provided module path '$modulePath' doesn't point to an existing directory")
         }

    import scala.sys.process._
    println(idrisArguments)
    println(s"Idris is located at $idrisPath")
    def run(pwd: Option[AbsolutePath], args: String*): Unit = {
      val params = Seq(idrisPath.toString) ++ args
      println("Going to execute: ")
      println(params.mkString(" "))
      Process(
        params,
        pwd.map(_.toJava.toFile)).!
      println("Executed")
      ()
    }

    def runLocal(args: String*): Unit =
      run(None, args :_*)

    def tempDir: Either[String, AbsolutePath] =
      resources.temporaryDirectory.mapError(_.toString).run

    def install(ipzPath: AbsolutePath): Either[String, AbsolutePath] =
      for {
        targetModuleExtractionPath <- tempDir
        _                          <- unzip(ipzPath, targetModuleExtractionPath).mapError(_.toString)
        ipkgPath                   <- findModuleIpkgPath(targetModuleExtractionPath)
        content                    <- readUTF8(ipkgPath)
                                          .mapError(error => s"The content of the file could not be read because: $error")
        _                           = println(s"The content of the file is:\n$content")
        ipkgMeta                   <- parse(content)
                                          .mapError(errors => s"The content could not be parsed due to:\n${errors.mkString("\n")}")
        sourcedir                  <- ipkgMeta
                                          .sourcedir
                                          .getOrElse(Path.dot) match {
                                             case r: RelativePath => Right(r)
                                             case a: AbsolutePath => Left(s"Only relative sourcedirs are accepted, but the module has '$a' configured")
                                           }
      } yield {
        targetModuleExtractionPath / sourcedir
      }

    for {
      installedModules  <-  idrisModules.map(install).sequence.mapError(es => es.mkString("\n"))
    } yield {
      val imSearchPaths = installedModules.flatMap(m => List("-i", m.toString))
      runLocal((imSearchPaths ++ idrisArguments) :_*)
      ()
    }

  }

  def create(idrisPath: AbsolutePath, modulePath: AbsolutePath, target: AbsolutePath): RU = {

    println(s"\n The path is: $modulePath")

    println(s"Idris is located at $idrisPath")
    def run(pwd: AbsolutePath, args: String*): Unit = {
      import scala.sys.process._
      val params = Seq(idrisPath.toString) ++ args
      println("Going to execute: ")
      println(params.mkString(" "))
      Process(
        params,
        Some(pwd.toJava.toFile)).!
      println("Executed")
      ()
    }

    for {

      root      <- modulePath.parent
                       .toRight(s"The package file at '$modulePath' seems to have no parent")
      _          = run(root, "--build", modulePath.toString)

      content   <- readUTF8(modulePath)
                       .mapError(error => s"The content of the file could not be read because: $error")
      _          = println(s"The content of the file is:\n$content")

      ipkgMeta  <- parse(content)
                       .mapError(errors => s"The content could not be parsed due to:\n${errors.mkString("\n")}")
      _          = println(s"The parsed content of the file is:\n$ipkgMeta")

      _         <- target
                     .when(_.isNoDirectory)
                     .orFail(s"'$target' can not be overwriten, because it's a directory")

      _          = println(s"The zip will be created at: $target")
      _         <- target
                       .whenFile{ f =>
                          println(s"'$target' is an existing file and is going to be deleted")
                          f.rm
                           .mapError(error => s"'$target' already exists and can't be deleted due to '$error'") }
                       .whenDir{ d => Left(s"'$target' is a directory and a file can't be created there") }
                       .whenNothing{ _ => RU }
      _         <- target.makeParents
                       .map(_.mapError(error => s"target directory could not be created, due to: $error"))
                       .toRight("Target directory could not be created because, for some reason, the target file has no parent")
                       .flatMap(identity)
      _          = println(s"The root of the zip file is going to be picked up from '$root'")

      ipkgFile  <- modulePath.last
                       .toRight(s"The package file at '$modulePath' seems to have no name")
      _          = println(s"The name of the ipkg file is: $ipkgFile")

      modules   <- ipkgMeta
                       .modules
                       .flatMap(m =>
                          List(
                            Path(m.replace('.', '/') + ".idr")
                              .flatMap {
                                 case r: RelativePath => Right(r)
                                 case a: AbsolutePath => Left(s"All modules should be relative paths, but '$a' is absolute")
                               },
                            Path(m.replace('.', '/') + ".ibc")
                              .flatMap {
                                 case r: RelativePath => Right(r)
                                 case a: AbsolutePath => Left(s"All modules should be relative paths, but '$a' is absolute")
                               }
                          )
                        ).sequence
                       .mapError(errors => s"Some of the modules are incorrect due to:\n${errors.mkString("\n")}")
      _          = println(s"The modules are: $modules")

      sourcedir <- ipkgMeta
                       .sourcedir
                       .getOrElse(Path.dot) match {
                          case r: RelativePath => Right(r)
                          case a: AbsolutePath => Left(s"Only relative sourcedirs are accepted, but the module has '$a' configured")
                        }

      content    = ipkgFile :: modules.map(mod => sourcedir / mod)
      _          = println(s"The zip file content is: $content")
      _         <- zip( target, root, content )
                       .mapError(error => s"The zip could not be created due to: $error")
    } yield {
      ()
    }
  }

  def toAbsolutePath(candidate: String, description: String): R[AbsolutePath] =
    Path(candidate)
      .map {
         case ap: AbsolutePath => ap
         case rp: RelativePath => Path.current / rp
       }
      .mapError{error => s"Error parsing the $description path '$candidate': is not a valid path"}

  sealed trait Arguments
  object Arguments {
    case class Create(idrisPath: AbsolutePath, modulePath: AbsolutePath, targetPath: AbsolutePath) extends Arguments
    case class Idris(idrisPath: AbsolutePath, idrisModules: List[AbsolutePath], idrisArguments: List[String]) extends Arguments
  }

  def extractListOfModules(arguments: List[String]): R[(List[AbsolutePath], List[String])] = {
    def impl(arguments: List[String]):(List[String], List[String]) =
      arguments match {
        case "--ip" :: modulePathString :: rest =>
          val (restOfModules, restOfArgs) = impl(rest)
          (modulePathString :: restOfModules, restOfArgs)
        case h :: rest =>
          val (restOfModules, restOfArgs) = impl(rest)
          (restOfModules, h :: restOfArgs)
        case Nil => (Nil, Nil)
      }

    val (mods, restOfArgs) = impl(arguments)

    mods
      .map(m => toAbsolutePath(m, "idris module"))
      .sequence
      .map(ms => (ms, restOfArgs))
      .mapError(errors => errors.mkString("\n"))
  }

  def parseArguments(arguments: List[String]): R[Arguments] =
    arguments match {
      case "create" :: idrisPathString :: modulePathString :: targetPathString :: Nil =>
        ((idrisPathString, "idris"), (modulePathString, "module"), (targetPathString, "target"))
          .map{case (p, d) => toAbsolutePath(p, d)}
          .sequence
          .map {
             case (idrisPath, modulePath, targetPath) =>
              Arguments.Create(idrisPath, modulePath, targetPath)
           }
          .mapError {errors => errors.mkString("", "\n", "\n") + USAGE}
      case "idris" :: idrisPathString :: idrisArguments =>
        toAbsolutePath(idrisPathString, "idris")
          .flatMap { case idrisPath =>
             extractListOfModules(idrisArguments)
               .map{case (mods, args) =>
                  Arguments.Idris(idrisPath, mods, args)
                }
           }
      case _ =>
        Left("Wrong arguments\n" + USAGE)
    }

  val USAGE =
    """|
       |USAGE:
       |  ip create MODULE TARGET
       |""".stripMargin
}

