package tailcall.runtime

import caliban.CalibanError
import tailcall.runtime.http.HttpClient
import tailcall.runtime.internal.JsonPlaceholderConfig
import tailcall.runtime.model.Config
import tailcall.runtime.service.DataLoader.HttpDataLoader
import tailcall.runtime.service._
import zio.test.Assertion.equalTo
import zio.test.TestAspect.timeout
import zio.test.{ZIOSpecDefault, assertZIO}
import zio.{Cause, ZIO, durationInt}

object Config2GraphQLSpec extends ZIOSpecDefault {

  def execute(
    config: Config
  )(query: String): ZIO[HttpDataLoader with GraphQLGenerator, CalibanError.ValidationError, String] = {
    for {
      graphQL     <- config.toBlueprint.toGraphQL
      interpreter <- graphQL.interpreter
      response    <- interpreter.execute(query)
      _ <- ZIO.foreachDiscard(response.errors)(error => ZIO.logErrorCause("GraphQL Execution Error", Cause.fail(error)))
    } yield response.data.toString
  }

  override def spec =
    suite("config to graphql")(
      test("users name") {
        val program = execute(JsonPlaceholderConfig.config)(""" query { users {name} } """)

        val expected = """{"users":[
                         |{"name":"Leanne Graham"},
                         |{"name":"Ervin Howell"},
                         |{"name":"Clementine Bauch"},
                         |{"name":"Patricia Lebsack"},
                         |{"name":"Chelsey Dietrich"},
                         |{"name":"Mrs. Dennis Schulist"},
                         |{"name":"Kurtis Weissnat"},
                         |{"name":"Nicholas Runolfsdottir V"},
                         |{"name":"Glenna Reichert"},
                         |{"name":"Clementina DuBuque"}
                         |]}""".stripMargin.replace("\n", "").trim
        assertZIO(program)(equalTo(expected))
      },
      test("user name") {
        val program = execute(JsonPlaceholderConfig.config)(""" query { user(id: 1) {name} } """)
        assertZIO(program)(equalTo("""{"user":{"name":"Leanne Graham"}}"""))
      },
      test("post body") {
        val program  = execute(JsonPlaceholderConfig.config)(""" query { post(id: 1) { title } } """)
        val expected =
          """{"post":{"title":"sunt aut facere repellat provident occaecati excepturi optio reprehenderit"}}"""
        assertZIO(program)(equalTo(expected))
      },
      test("user company") {
        val program  = execute(JsonPlaceholderConfig.config)(""" query {user(id: 1) { company { name } } }""")
        val expected = """{"user":{"company":{"name":"Romaguera-Crona"}}}"""
        assertZIO(program)(equalTo(expected))
      },
      test("user posts") {
        val program  = execute(JsonPlaceholderConfig.config)(""" query {user(id: 1) { posts { title } } }""")
        val expected =
          """{"user":{"posts":[{"title":"sunt aut facere repellat provident occaecati excepturi optio reprehenderit"},
            |{"title":"qui est esse"},
            |{"title":"ea molestias quasi exercitationem repellat qui ipsa sit aut"},
            |{"title":"eum et est occaecati"},
            |{"title":"nesciunt quas odio"},
            |{"title":"dolorem eum magni eos aperiam quia"},
            |{"title":"magnam facilis autem"},
            |{"title":"dolorem dolore est ipsam"},
            |{"title":"nesciunt iure omnis dolorem tempora et accusantium"},
            |{"title":"optio molestias id quia eum"}]}}""".stripMargin.replace("\n", "").trim
        assertZIO(program)(equalTo(expected))
      },
      test("post user") {
        val program  = execute(JsonPlaceholderConfig.config)(""" query {post(id: 1) { title user { name } } }""")
        val expected =
          """{"post":{"title":"sunt aut facere repellat provident occaecati excepturi optio reprehenderit","user":{"name":"Leanne Graham"}}}"""
        assertZIO(program)(equalTo(expected))
      },
      test("create user") {
        val program = execute(JsonPlaceholderConfig.config)(
          """ mutation { createUser(user: {name: "test", email: "test@abc.com", username: "test"}) { id } } """
        )
        assertZIO(program)(equalTo("""{"createUser":{"id":11}}"""))
      },
    ).provide(GraphQLGenerator.default, HttpClient.default, DataLoader.http) @@ timeout(10 seconds)
}
