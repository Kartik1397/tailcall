package tailcall.runtime.service

import caliban.Value
import caliban.schema.Step
import tailcall.runtime.internal.DynamicValueUtil
import tailcall.runtime.model
import tailcall.runtime.model.{Blueprint, Context}
import tailcall.runtime.service.DataLoader.HttpDataLoader
import tailcall.runtime.service.StepGenerator.StepResult
import tailcall.runtime.transcoder.Transcoder
import zio.query.ZQuery
import zio.schema.DynamicValue
import zio.{ZIO, ZLayer}

trait StepGenerator {
  def resolve(document: Blueprint): StepResult[HttpDataLoader]
}

object StepGenerator {
  def default: ZLayer[Any, Nothing, StepGenerator] = EvaluationRuntime.default >>> live

  def live: ZLayer[EvaluationRuntime, Nothing, StepGenerator] = {
    ZLayer(ZIO.service[EvaluationRuntime].map(rtm =>
      new StepGenerator {
        override def resolve(document: Blueprint): StepResult[HttpDataLoader] =
          BlueprintGenerator(rtm, document).resolve
      }
    ))
  }

  def resolve(document: Blueprint): ZIO[StepGenerator, Nothing, StepResult[HttpDataLoader]] =
    ZIO.serviceWith(_.resolve(document))

  final case class StepResult[R](query: Option[Step[R]], mutation: Option[Step[R]])

  final case class BlueprintGenerator(rtm: EvaluationRuntime, document: Blueprint) {
    val rootContext: Context = Context(DynamicValue(()))

    val stepRef: Map[String, Context => Step[HttpDataLoader]] = document.definitions
      .collect { case obj @ Blueprint.ObjectTypeDefinition(_, _, _) => (obj.name, ctx => fromObjectDef(obj, ctx)) }
      .toMap

    def resolve: StepResult[HttpDataLoader] = {

      val queryStep = for {
        query <- document.schema.flatMap(_.query)
        qStep <- stepRef.get(query)
      } yield qStep(rootContext)

      val mutationStep = for {
        mutation <- document.schema.flatMap(_.mutation)
        mStep    <- stepRef.get(mutation)
      } yield mStep(rootContext)

      StepResult(queryStep, mutationStep)
    }

    def fromFieldDefinition(field: Blueprint.FieldDefinition, ctx: Context): Step[HttpDataLoader] = {
      Step.FunctionStep { args =>
        val context = ctx
          .copy(args = args.view.mapValues(Transcoder.toDynamicValue(_).getOrElse(DynamicValue(()))).toMap)
        field.resolver match {
          case Some(resolver) =>
            val step = for {
              value <- rtm.evaluate(resolver)(DynamicValue(context))
              step = fromType(field.ofType, context.copy(value = value, parent = Option(ctx)))
            } yield step

            Step.QueryStep(ZQuery.fromZIO(step))
          case None           =>
            val value = DynamicValue(DynamicValueUtil.getPath(context.value, field.name :: Nil))
            fromType(field.ofType, context.copy(value = value))
        }
      }
    }

    def fromObjectDef(obj: Blueprint.ObjectTypeDefinition, ctx: Context): Step[HttpDataLoader] = {
      Step.ObjectStep(obj.name, obj.fields.map(field => field.name -> fromFieldDefinition(field, ctx)).toMap)
    }

    def fromType(tpe: model.Blueprint.Type, ctx: Context): Step[HttpDataLoader] =
      tpe match {
        case model.Blueprint.NamedType(name, _)  => stepRef.get(name) match {
            case Some(value) => value(ctx)
            case None        => Step.PureStep(Transcoder.toResponseValue(ctx.value).getOrElse(Value.NullValue))
          }
        case model.Blueprint.ListType(ofType, _) => ctx.value match {
            case DynamicValue.Sequence(values) => Step
                .ListStep(values.map(value => fromType(ofType, ctx.copy(value = value))).toList)
            case _                             => Step.ListStep(List(fromType(ofType, ctx)))
          }
      }
  }
}
