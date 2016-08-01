package com.eclipsesource.schema.internal.validators

import com.eclipsesource.schema.{SchemaObject, SchemaType}
import com.eclipsesource.schema.internal.SchemaRefResolver.SchemaResolutionContext
import com.eclipsesource.schema.internal._
import com.eclipsesource.schema.internal.validation.{Rule, VA}
import play.api.libs.json._

import scala.annotation.tailrec

import scalaz.{Failure, Success}

object AnyConstraintValidator {

  def validate(json: JsValue, schema: SchemaType, resolutionContext: SchemaResolutionContext): VA[JsValue] = {
    val reader: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] = for {
      allOfRule <- validateAllOf
      anyOfRule <- validateAnyOf
      oneOfRule <- validateOneOf
      enumRule  <- validateEnum
      notRule   <- validateNot
    } yield allOfRule |+| anyOfRule |+| oneOfRule |+| enumRule |+| notRule
    reader.run((schema, resolutionContext)).repath(_.compose(resolutionContext.instancePath)).validate(json)
  }

  def validateNot: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] =
    scalaz.Reader { case (schema ,context) =>
      Rule.fromMapping { json =>
        schema.constraints.any.not.map(schema =>
          if (schema.validate(json, context).isFailure) {
            Success(json)
          } else {
            failure(
              s"$json matches schema '$schema' although it should not.",
              context.schemaPath,
              context.instancePath,
              json
            )
          }
        ).getOrElse(Success(json))
      }
    }


  def validateAllOf: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] =
    scalaz.Reader { case (schema, context) =>
      Rule.fromMapping { json =>
        schema.constraints.any.allOf.map(
          schemas => {
            val mergedSchemas = mergeSchemas(schema, schemas)
            val allValidationResults: Seq[VA[JsValue]] = mergedSchemas.map(_.validate(json, context))
            val allMatch = allValidationResults.forall(_.isSuccess)
            if (allMatch) {
              Success(json)
            } else {
              failure(
                s"Instance does not match all schemas",
                context.schemaPath,
                context.instancePath,
                json,
                collectFailures(allValidationResults, "/allOf")
              )
            }
          }
        ).getOrElse(Success(json))
      }
    }

  def validateAnyOf: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] = {

    @tailrec
    def untilFirstSuccess(json: JsValue, baseSchema: SchemaType, context: SchemaResolutionContext,
                          schemas: List[SchemaType], results: List[VA[JsValue]]): List[VA[JsValue]] = schemas match {
      case s::ss =>
        val mergedSchema = mergeSchema(s, baseSchema)
        mergedSchema.validate(json, context) match {
          case Success(e) => Nil
          case failure@Failure(errors) => untilFirstSuccess(json, baseSchema, context, ss, failure :: results)
        }
      case Nil => results.reverse
    }

    scalaz.Reader { case (schema, context) =>
      Rule.fromMapping {
        json => {
          schema.constraints.any.anyOf match {
            case Some(schemas) =>
              untilFirstSuccess(json, schema, context, schemas.toList, List.empty) match {
                case Nil => Success(json)
                case errors => failure(
                  "Instance does not match any of the schemas",
                  context.schemaPath,
                  context.instancePath,
                  json,
                  collectFailures(errors, "/anyOf")
                )
              }
            case None => Success(json)
          }
        }
      }
    }
  }

  def validateOneOf: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] =
    scalaz.Reader { case (schema, context) =>
      Rule.fromMapping { json =>
        schema.constraints.any.oneOf.map(
          schemas => {
            val mergedSchemas = mergeSchemas(schema, schemas)
            val allValidationResults = mergedSchemas.map(_.validate(json, context))
            allValidationResults.count(_.isSuccess) match {
              case 0 =>
                failure(
                  s"Instance does not match any schema",
                  context.schemaPath,
                  context.instancePath,
                  json,
                  collectFailures(allValidationResults, "/oneOf")
                )
              case 1 => Success(json)
              case _ =>
                val matchedPaths = allValidationResults.zipWithIndex.foldLeft(List.empty[String]) {
                  case (arr, (Success(result), idx)) =>
                    arr :+ s"/oneOf/$idx"
                  case (arr, _) => arr
                }
                failure(
                  s"Instance matches more than one schema",
                  context.schemaPath,
                  context.instancePath,
                  json,
                  Json.obj("matched" -> matchedPaths)
                )
            }
          }
        ).getOrElse(Success(json))
      }
    }

  def validateEnum: scalaz.Reader[(SchemaType, SchemaResolutionContext), Rule[JsValue, JsValue]] = {
    scalaz.Reader { case (schema, context) =>
      val enums = schema.constraints.any.enum
      Rule.fromMapping { json =>
        enums match {
          case Some(values) if values.contains(json) => Success(json)
          case Some(values) =>
            failure(
              "Instance is invalid enum value",
              context.schemaPath,
              context.instancePath,
              json,
              Json.obj(
                "enum" -> values
              )
            )
          case None => Success(json)
        }
      }
    }
  }

  private def collectFailures(results: Seq[VA[JsValue]], prefix: String): JsObject = {

    def repath(prefix: String)(obj: JsObject): JsObject = {
      val fields = obj.fields.map {
        case ("schemaPath", JsString(schemaPath)) if schemaPath.startsWith("#") => ("schemaPath", JsString(s"#$prefix${schemaPath.drop(1)}"))
        case ("schemaPath", JsString(schemaPath)) => ("schemaPath", JsString(s"$prefix$schemaPath"))
        case field => field
      }
      JsObject(fields)
    }

    results.zipWithIndex.foldLeft(Json.obj()) {
      case (obj, (Failure(errors), idx)) =>
        // TODO: why distinct?
        obj ++ Json.obj(s"$prefix/$idx" ->
          JsArray(
            SchemaUtil.toJson(errors.distinct).value.map {
              case obj: JsObject => repath(s"$prefix/$idx")(obj)
              case js => js
            }
          ))
      case (obj, _) => obj
    }
  }

  private def mergeSchema(schema: SchemaType, otherSchema: SchemaType): SchemaType = (schema, otherSchema) match {
    case (s1: SchemaObject, s2: SchemaObject) => s1.copy(properties = s1.properties ++ s2.properties)
    case _ => schema
  }

  private def mergeSchemas(schema: SchemaType, subSchemas: Seq[SchemaType]): Seq[SchemaType]  = schema match {
    case obj: SchemaObject =>
      subSchemas.map {
        case otherObj: SchemaObject => otherObj.copy(properties = obj.properties ++ otherObj.properties)
        case other => other
      }
    case _ => subSchemas
  }
}
