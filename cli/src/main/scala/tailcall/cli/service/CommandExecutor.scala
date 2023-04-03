package tailcall.cli.service

import caliban.GraphQL
import tailcall.cli.CommandADT
import tailcall.cli.CommandADT.{BlueprintOptions, Remote, SourceFormat, TargetFormat}
import tailcall.registry.SchemaRegistryClient
import tailcall.runtime.http.HttpClient
import tailcall.runtime.model.{Blueprint, Digest, Endpoint, Postman}
import tailcall.runtime.service._
import tailcall.runtime.transcoder.Endpoint2Config.NameGenerator
import tailcall.runtime.transcoder.{Postman2Endpoints, Transcoder}
import zio.http.URL
import zio.json.EncoderOps
import zio.{Console, Duration, ExitCode, ZIO, ZLayer}

import java.io.IOException
import java.nio.file.Path

trait CommandExecutor {
  def dispatch(command: CommandADT): ZIO[Any, Nothing, ExitCode]
}

object CommandExecutor {
  final case class Live(
    graphQLGen: GraphQLGenerator,
    configFile: ConfigFileIO,
    fileIO: FileIO,
    registry: SchemaRegistryClient,
  ) extends CommandExecutor {
    def timed[R, E >: IOException, A](program: ZIO[R, E, A]): ZIO[R, E, A] =
      for {
        start <- zio.Clock.nanoTime
        a     <- program.logError
        end   <- zio.Clock.nanoTime
        _     <- Console.printLine {
          val duration = Duration.fromNanos(end - start)
          s"\n\uD83D\uDC4D Completed in ${duration.toMillis} ms."
        }
      } yield a

    private def postman2GraphQL(files: ::[Path], dSLFormat: DSLFormat): ZIO[Any, Throwable, String] = {
      val nameGen = NameGenerator.incremental
      for {
        postman <- ZIO.foreachPar(files.toList)(path => fileIO.readJson[Postman](path.toFile))
        config  <- ZIO.foreachPar(postman)(
          Transcoder.toConfig(_, Postman2Endpoints.Config(true, nameGen)).provide(DataLoader.http, HttpClient.default)
        )
        out     <- dSLFormat.encode(config.reduce(_ mergeRight _).compress)
          .catchAll(err => ZIO.fail(new RuntimeException(err)))
      } yield out
    }

    def writeGeneratedFile[R, E >: Throwable](content: ZIO[R, E, String], write: Option[Path]): ZIO[R, E, Unit] =
      for {
        out <- content
        _   <- write match {
          case Some(path) => for {
              _ <- Console.printLine(Fmt.heading(s"Generated File: ${path.toString}"))
              _ <- fileIO.write(path.toFile, out)
            } yield ()
          case None       => for {
              _ <- Console.printLine(Fmt.heading("Generated Output:"))
              _ <- Console.printLine(out)
            } yield ()
        }
      } yield ()

    override def dispatch(command: CommandADT): ZIO[Any, Nothing, ExitCode] =
      timed {

        command match {
          case CommandADT.Generate(files, sourceFormat, targetFormat, write) =>
            val output: ZIO[Any, Throwable, String] = (sourceFormat, targetFormat) match {
              case (SourceFormat.Postman, TargetFormat.Config(dSLFormat))          => postman2GraphQL(files, dSLFormat)
              case (SourceFormat.SchemaDefinitionLanguage, TargetFormat.JsonLines) => for {
                  content   <- ZIO.foreachPar(files.toList)(path => fileIO.read(path.toFile))
                  jsonLines <- ZIO.foreachPar(content)(Transcoder.toJsonLines(_))
                } yield jsonLines.mkString("\n")

              case _ => ZIO.fail(new RuntimeException(
                  s"Unsupported format combination ${sourceFormat.name} to ${targetFormat.name}"
                ))
            }
            writeGeneratedFile(output, write)

          case CommandADT.Check(files, remote, options) => for {
              config <- configFile.readAll(files.map(_.toFile))
              blueprint = config.toBlueprint
              digest    = blueprint.digest
              seq0      = Seq("Digest" -> s"${digest.hex}")
              seq1 <- remote match {
                case Some(remote) => registry.get(remote, digest).map {
                    case Some(_) => seq0 :+ ("Playground", Fmt.playground(remote, digest))
                    case None    => seq0 :+ ("Playground" -> "Unavailable")
                  }
                case None         => ZIO.succeed(seq0)
              }
              _    <- Console.printLine(Fmt.success("No errors found."))
              _    <- Console.printLine(Fmt.table(seq1))
              _    <- blueprintDetails(blueprint, options)
            } yield ()
          case CommandADT.Remote(base, command)         => command match {
              case Remote.Publish(path) => for {
                  config    <- configFile.readAll(path.map(_.toFile))
                  blueprint <- Transcoder.toBlueprint(config).toZIO
                  digest    <- registry.add(base, blueprint)
                  _         <- Console.printLine(Fmt.success("Deployment was completed successfully."))
                  _         <- Console.printLine(
                    Fmt.table(Seq("Digest" -> s"${digest.hex}", "Playground" -> Fmt.playground(base, digest)))
                  )
                } yield ()
              case Remote.Drop(digest)  => for {
                  _ <- registry.drop(base, digest)
                  _ <- Console.printLine(Fmt.success(s"Blueprint dropped successfully."))
                  _ <- Console.printLine(Fmt.table(Seq("Digest" -> s"${digest.hex}")))
                } yield ()

              case Remote.ListAll(index, offset) => for {
                  blueprints <- registry.list(base, index, offset)
                  _          <- Console.printLine(Fmt.blueprints(blueprints))
                  _          <- Console
                    .printLine(Fmt.table(Seq("Server" -> base.encode, "Total Count" -> s"${blueprints.length}")))
                } yield ()

              case Remote.Show(digest, options) => for {
                  maybe <- registry.get(base, digest)
                  _     <- Console.printLine(Fmt.table(Seq(
                    "Digest"     -> s"${digest.hex}",
                    "Playground" -> maybe.map(_ => Fmt.playground(base, digest)).getOrElse(Fmt.meta("Unavailable")),
                  )))
                  _     <- maybe match {
                    case Some(blueprint) => blueprintDetails(blueprint, options)
                    case _               => ZIO.unit
                  }
                } yield ()
            }
        }
      }.exitCode

    private def blueprintDetails(blueprint: Blueprint, options: BlueprintOptions): ZIO[Any, IOException, Unit] = {
      for {
        _ <- Console.printLine(Fmt.heading("Blueprint:\n") ++ Fmt.blueprint(blueprint)).when(options.blueprint)
        _ <- Console.printLine(Fmt.heading("GraphQL Schema:\n") ++ Fmt.graphQL(graphQLGen.toGraphQL(blueprint)))
          .when(options.schema)
        _ <- Console.printLine(Fmt.heading("Endpoints:\n") ++ endpoints(blueprint.endpoints)).when(options.endpoints)
      } yield ()
    }

    private def endpoints(endpoints: List[Endpoint]): String =
      List[String](
        endpoints.map[String](endpoint =>
          List[String](
            "\n",
            Fmt.heading(s"${endpoint.method.name} ${endpoint.url}"),
            Fmt.heading(s"Input Schema: ") + s"${endpoint.input.fold("Any")("\n" + _.toJsonPretty)}",
            Fmt.heading(s"Output Schema: ") + s" ${endpoint.output.fold("Nothing")("\n" + _.toJsonPretty)}",
          ).mkString("\n")
        ).mkString("\n")
      ).mkString("\n")

  }
  def execute(command: CommandADT): ZIO[CommandExecutor, Nothing, ExitCode] =
    ZIO.serviceWithZIO[CommandExecutor](_.dispatch(command))

  type Env = GraphQLGenerator with ConfigFileIO with FileIO with SchemaRegistryClient

  def live: ZLayer[Env, Nothing, CommandExecutor] = ZLayer.fromFunction(Live.apply _)

  def default: ZLayer[Any, Throwable, CommandExecutor] =
    (GraphQLGenerator.default ++ ConfigFileIO.default ++ FileIO.default ++ SchemaRegistryClient.default) >>> live

  object Fmt {
    def success(str: String): String = fansi.Str(str).overlay(fansi.Color.Green).render

    def heading(str: String): String = fansi.Str(str).overlay(fansi.Bold.On).render

    def caption(str: String): String = fansi.Str(str).overlay(fansi.Color.DarkGray).render

    def meta(str: String): String = fansi.Str(str).overlay(fansi.Color.LightYellow).render

    def graphQL(graphQL: GraphQL[_]): String = { graphQL.render }

    def blueprint(blueprint: Blueprint): String = { blueprint.toJsonPretty }

    def blueprints(blueprints: List[Blueprint]): String = {
      Fmt.table(blueprints.zipWithIndex.map { case (blueprint, index) => ((index + 1).toString, blueprint.digest.hex) })
    }

    def table(labels: Seq[(String, String)]): String = {
      def maxLength = labels.map(_._1.length).max + 1
      def padding   = " " * maxLength
      labels.map { case (key, value) => heading((key + ":" + padding).take(maxLength)) + " " ++ value }.mkString("\n")
    }

    def playground(url: URL, digest: Digest): String = s"${url.encode}/graphql/${digest.hex}."
  }
}
