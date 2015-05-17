/*
 * Copyright 2014 The Guardian
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import java.io.File

import controllers.Actions._
import lib.MailType.proposedMailByTypeFor
import lib._
import lib.aws.SES._
import lib.aws.SesAsyncHelpers._
import lib.github.{GitHubAuthResponse, MinimalGHPerson, PullRequestId, RepoName}
import org.eclipse.jgit.lib.ObjectId
import org.kohsuke.github._
import play.api.Logger
import play.api.Play.current
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._
import views.html.pullRequestSent

import scala.collection.convert.wrapAll._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

object Application extends Controller {

  type AuthRequest[A] = AuthenticatedRequest[A, GitHub]

  val repoWhiteList= Set("git/git", "submitgit/pretend-git")

  val AccessTokenSessionKey = "githubAccessToken"

  import GithubAppConfig._
  val ghAuthUrl = s"$authUrl?client_id=$clientId&scope=$scope"

  def index = Action { implicit req =>
    Ok(views.html.index())
  }

  val bareAccessTokenRequest = {
    import GithubAppConfig._
    WS.url(accessTokenUrl)
      .withQueryString(("client_id", clientId), ("client_secret", clientSecret))
      .withHeaders(("Accept", "application/json"))
  }

  val redirectToGitRepo: Result = Redirect(routes.Application.listPullRequests(RepoName("git", "git")))

  def oauthCallback(code: String) = Action.async {
    for (response <- bareAccessTokenRequest.withQueryString("code" -> code).post("")) yield {
      val accessToken = response.json.validate[GitHubAuthResponse].get.access_token
      val user = GitHub.connectUsingOAuth(accessToken).getMyself
      redirectToGitRepo.withSession(
        AccessTokenSessionKey -> accessToken,
        MinimalGHPerson(user.getLogin, user.getAvatarUrl).sessionTuple
      )
    }
  }

  def logout = Action {
    Redirect(routes.Application.index()).withNewSession
  }

  def listPullRequests(repoId: RepoName) = githubRepoAction(repoId) { implicit req =>
    val myself = req.gitHub.getMyself
    val openPRs = req.repo.getPullRequests(GHIssueState.OPEN)
    val (userPRs, otherPRs) = openPRs.partition(_.getUser.equals(myself))
    val alternativePRs = otherPRs.toStream ++ req.repo.listPullRequests(GHIssueState.CLOSED).toStream

    Ok(views.html.listPullRequests(userPRs, alternativePRs.take(3)))
  }

  def reviewPullRequest(prId: PullRequestId) = githubPRAction(prId).async { implicit req =>
    val myself = req.gitHub.getMyself

    for (proposedMailByType <- proposedMailByTypeFor(req)) yield {
      Ok(views.html.reviewPullRequest(req.pr, myself, proposedMailByType))
    }
  }

  def acknowledgePreview(prId: PullRequestId, headCommit: ObjectId, signature: String) =
    (githubAction() andThen verifyCommitSignature(headCommit, Some(signature))).async {
      implicit req =>
        val userEmail = req.userEmail.getEmail

        def whatDoWeTellTheUser(userEmail: String, verificationStatusOpt: Option[VerificationStatus]): Future[Option[(String, String)]] = {
          verificationStatusOpt match {
            case Some(VerificationStatus.Success) => // Nothing to do
              Future.successful(None)
            case Some(VerificationStatus.Pending) => // Remind user to click the link in their email
              Future.successful(Some("notifyEmailVerification" -> userEmail))
            case _ => // send verification email, tell user to click on it
              ses.sendVerificationEmailTo(userEmail).map(_ => Some("notifyEmailVerification" -> userEmail))
          }
        }

        for {
          verificationStatusOpt <- ses.getIdentityVerificationStatusFor(userEmail)
          flashOpt <- whatDoWeTellTheUser(userEmail, verificationStatusOpt)
        } yield {
          Redirect(routes.Application.reviewPullRequest(prId)).addingToSession(PreviewSignatures.keyFor(headCommit) -> signature).flashing(flashOpt.toSeq: _*)
        }
    }

  def mailPullRequest(prId: PullRequestId, mailType: MailType) = (githubPRAction(prId) andThen mailChecks(mailType)).async {
    implicit req =>

      val addresses = mailType.addressing(req.user)

      def emailFor(patch: Patch)= Email(
          addresses,
          subject = (mailType.subjectPrefix ++ Seq(patch.subject)).mkString(" "),
          bodyText = s"${patch.body}\n---\n${mailType.footer(req.pr)}"
        )

      for (commitsAndPatches <- req.commitsAndPatchesF) yield {
        for (initialMessageId <- ses.send(emailFor(commitsAndPatches.head._2))) {
          for ((commit, patch) <- commitsAndPatches.drop(1)) {
            ses.send(emailFor(patch).copy(headers = Seq("References" -> initialMessageId, "In-Reply-To" -> initialMessageId)))
          }
        }

        // pullRequest.comment("Closed by submitgit")
        // pullRequest.close()
        Ok(pullRequestSent(req.pr, req.user, mailType))
      }
  }

  lazy val gitCommitId = {
    val g = gitCommitIdFromHerokuFile
    Logger.info(s"Heroku dyno commit id $g")
    g.getOrElse(app.BuildInfo.gitCommitId)
  }

  def gitCommitIdFromHerokuFile: Option[String]  = {
    val file = new File("/etc/heroku/dyno")
    val existingFile = if (file.exists && file.isFile) Some(file) else None

    Logger.info(s"Heroku dyno metadata $existingFile")

    for {
      f <- existingFile
      text <- (Json.parse(scala.io.Source.fromFile(f).mkString) \ "release" \ "commit").asOpt[String]
      objectId <- Try(ObjectId.fromString(text)).toOption
    } yield objectId.name
  }
}



