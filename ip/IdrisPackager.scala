package ip

import ip.fileops._
import ip.fileops.path._
import ip.ipkgops._
import ip.zipops._
import ip.tupleops._
import ip.result._
import ip.logger.Logger
import ip.idris._
import ip.describe._

object IdrisPackager {

  type R[+T] = Result[String, T]
  type RU = RUnit[String]
  val RU: RU = Result.success(())

  def main(args: Array[String]): Unit = {

    println()

    implicit val log: Logger =
      ip.logger.Logger.Console.trace

    val argumentStrings =
      args.toList

    val result =
      for {
        arguments <- parseArguments(argumentStrings)
        _         <- arguments match {
                       case Arguments.Create(idrisPath, modulePath, targetPath, dependencies) =>
                         val idris = Idris.Plain(idrisPath, Path.current)
                         create(idris, modulePath, targetPath, dependencies)
                       case Arguments.Idris(idrisPath, idrisModules, idrisArguments) =>
                         val idris = Idris.Plain(idrisPath, Path.current)
                         runIdris(idris, idrisModules, idrisArguments)
                     }

      } yield {
        ()
      }


    result.run match {
      case Left(error) => log.error(error)
      case _ =>
    }
  }


  private def findModuleIpkgPath(modulePath: AbsolutePath): R[AbsolutePath] =
    modulePath
      .whenDir{dir =>
         dir.find(raw".*\.ipkg") transform {
           case Right(target :: Nil) => Result.success(modulePath / target)
           case Right(Nil) => Result.failure(s"Provided module path '$modulePath' doesn't containt any ipkg file")
           case Right(_) => Result.failure(s"Provided module path '$modulePath' containts more than one ipkg file and choosing is imposible")
           case Left(cause) => Result.failure(s"Trying to locate an ipkg file at '$modulePath', something went wrong due to:\n$cause")
         }
       }
      .otherwise {_ =>
         Result.failure(s"Provided module path '$modulePath' doesn't point to an existing directory")
       }

  private def tempDir: R[AbsolutePath] =
    resources.temporaryDirectory.mapError(_.toString)

  private def install(ipzPath: AbsolutePath): R[AbsolutePath] =
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
                                           case r: RelativePath => Result.success(r)
                                           case a: AbsolutePath => Result.failure(s"Only relative sourcedirs are accepted, but the module has '$a' configured")
                                         }
    } yield {
      targetModuleExtractionPath / sourcedir
    }

  def runIdris(idris: Idris, idrisModules: List[AbsolutePath], idrisArguments: List[String])(implicit logger: Logger): RU = {
    for {
      installedModules  <-  idrisModules.map(install).sequence.mapError(es => es.mkString("\n"))
      imSearchPaths = installedModules.flatMap(m => List("-i", m.toString))
      _ <- idris((imSearchPaths ++ idrisArguments) :_*).describe
    } yield {
      ()
    }

  }

  def create(idris: Idris, modulePath: AbsolutePath, target: AbsolutePath, dependencies: List[AbsolutePath])(implicit logger: Logger): RU = {

    println(s"\n The path is: $modulePath")
    println(dependencies)

    for {

      root      <- modulePath.parent
                       .toSuccess(s"The package file at '$modulePath' seems to have no parent")
      modules   <- dependencies.map(install).sequence.mapError(es => es.mkString("\n"))
      modPaths   = modules.flatMap(m => List("-i", m.toString))
      extraArgs  = List("--build", modulePath.toString) ++ modPaths
      _         <- idris.from(root)(extraArgs :_*).describe

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
                       .whenDir{ d => Result.failure(s"'$target' is a directory and a file can't be created there") }
                       .whenNothing{ _ => RU }
      _         <- target.makeParents
                       .map(_.mapError(error => s"target directory could not be created, due to: $error"))
                       .toSuccess("Target directory could not be created because, for some reason, the target file has no parent")
                       .flatMap(identity)
      _          = println(s"The root of the zip file is going to be picked up from '$root'")

      ipkgFile  <- modulePath.last
                       .toSuccess(s"The package file at '$modulePath' seems to have no name")
      _          = println(s"The name of the ipkg file is: $ipkgFile")

      modules   <- ipkgMeta
                       .modules
                       .flatMap(m =>
                          List(
                            Path(m.replace('.', '/') + ".idr")
                              .flatMap {
                                 case r: RelativePath => Result.success(r)
                                 case a: AbsolutePath => Result.failure(s"All modules should be relative paths, but '$a' is absolute")
                               },
                            Path(m.replace('.', '/') + ".ibc")
                              .flatMap {
                                 case r: RelativePath => Result.success(r)
                                 case a: AbsolutePath => Result.failure(s"All modules should be relative paths, but '$a' is absolute")
                               }
                          )
                        ).sequence
                       .mapError(errors => s"Some of the modules are incorrect due to:\n${errors.mkString("\n")}")
      _          = println(s"The modules are: $modules")

      sourcedir <- ipkgMeta
                       .sourcedir
                       .getOrElse(Path.dot) match {
                          case r: RelativePath => Result.success(r)
                          case a: AbsolutePath => Result.failure(s"Only relative sourcedirs are accepted, but the module has '$a' configured")
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

  implicit class ResultExtensionOps[E, T](val r: Result[E, T]) extends AnyVal {
    def describe(implicit desc: Describe[E]): Result[String, T] =
      r.mapError(_.description)
  }

  sealed trait Arguments
  object Arguments {
    case class Create(idrisPath: AbsolutePath, modulePath: AbsolutePath, targetPath: AbsolutePath, idrisModules: List[AbsolutePath]) extends Arguments
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
    extractListOfModules(arguments).flatMap{case (mods, restOfArgs) =>
      restOfArgs match {
        case "create" :: idrisPathString :: modulePathString :: targetPathString :: Nil =>
          ((idrisPathString, "idris"), (modulePathString, "module"), (targetPathString, "target"))
            .map{case (p, d) => toAbsolutePath(p, d)}
            .sequence
            .map {
               case (idrisPath, modulePath, targetPath) =>
                Arguments.Create(idrisPath, modulePath, targetPath, mods)
             }
            .mapError {errors => errors.mkString("", "\n", "\n") + USAGE}
        case "idris" :: idrisPathString :: idrisArguments =>
          toAbsolutePath(idrisPathString, "idris")
            .map { case idrisPath =>
                Arguments.Idris(idrisPath, mods, idrisArguments)
             }
        case _ =>
          //val e = new RuntimeException("Fooo")
          //val Right(p: AbsolutePath) = Path("/Foo/baar/thing.nix").run
          //ip.fileops.ReadError(p, e)
          //ip.fileops.readAllBytes
          Result.failure("Wrong arguments\n" + USAGE)
      }
    }

  val USAGE =
    """|
       |USAGE:
       |  ip create MODULE TARGET
       |""".stripMargin
}

