package controllers

import java.util.UUID

import javax.inject.{Inject, Singleton}
import actions.LoginAction
import extentions.{Hash, UUIDHelper}
import forms.FormsPlusMap
import models.User.date
import models.{Area, User}
import org.joda.time.{DateTime, DateTimeZone}
import org.webjars.play.WebJarsUtil
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.mvc.Results.Redirect
import play.api.mvc.{Call, InjectedController}
import services.{ApplicationService, EventService, NotificationService, UserService}

@Singleton
class UserController @Inject()(loginAction: LoginAction,
                               userService: UserService,
                               applicationService: ApplicationService,
                               notificationsService: NotificationService,
                               eventService: EventService)(implicit val webJarsUtil: WebJarsUtil) extends InjectedController with play.api.i18n.I18nSupport {

  def all = loginAction { implicit request =>
    if(request.currentUser.admin != true) {
      eventService.warn("ALL_USER_UNAUTHORIZED", s"Accès non autorisé à l'admin des utilisateurs")
      Unauthorized("Vous n'avez pas le droit de faire ça")
    } else {
      val users = userService.byArea(request.currentArea.id)
      val applications = applicationService.allByArea(request.currentArea.id, true)
      eventService.info("ALL_USER_SHOWED", s"Visualise la vue des utilisateurs")
      Ok(views.html.allUsers(request.currentUser, request.currentArea)(users, applications))
    }
  }

  private val timeZone = DateTimeZone.forID("Europe/Paris")

  def usersForm(implicit area: Area) = Form(
    single(
      "users" -> list(mapping(
        "id" -> optional(uuid).transform[UUID]({
          case None => UUID.randomUUID()
          case Some(id) => id
        }, { Some(_) }),
        "key" -> ignored("key"),  //TODO refactoring security
        "name" -> nonEmptyText.verifying(maxLength(100)),
        "qualite" -> nonEmptyText.verifying(maxLength(100)),
        "email" -> email.verifying(maxLength(200), nonEmpty),
        "helper" -> boolean,
        "instructor" -> boolean,
        "admin" -> boolean,
        "areas" -> ignored(List(area.id)),
        "creationDate" -> ignored(DateTime.now(timeZone)),
        "hasAcceptedCharte" -> boolean,
        "communeCode" -> default(nonEmptyText.verifying(maxLength(5)), "0"),
        "delegations" -> seq(tuple(
            "name" -> nonEmptyText,
            "email" -> email
          )
        ).transform[Map[String,String]]({ _.toMap }, { _.toSeq })
      )(User.apply)(User.unapply))
    )
  )

  def edit = loginAction { implicit request =>
    if(request.currentUser.admin != true) {
      eventService.warn("SHOW_EDIT_USER_UNAUTHORIZED", s"Accès non autorisé à l'admin des utilisateurs")
      Unauthorized("Vous n'avez pas le droit de faire ça")
    } else {
      implicit val area = request.currentArea
      val users = userService.allDBOnlybyArea(area.id)
      val form = usersForm.fill(users)
      eventService.info("EDIT_USER_SHOWED", s"Visualise la vue de modification des utilisateurs")
      Ok(views.html.editUsers(request.currentUser, request.currentArea)(form, users.length, routes.UserController.editPost()))
    }
  }

  def editPost = loginAction { implicit request =>
    if(request.currentUser.admin != true) {
      eventService.warn("POST_EDIT_USER_UNAUTHORIZED", s"Accès non autorisé à l'admin des utilisateurs")
      Unauthorized("Vous n'avez pas le droit de faire ça")
    } else {
      implicit val area = request.currentArea
      usersForm.bindFromRequest.fold(
        formWithErrors => {
          eventService.error("ADD_USER_ERROR", s"Essai de modification d'utilisateurs avec des erreurs de validation")
          BadRequest(views.html.editUsers(request.currentUser, request.currentArea)(formWithErrors, 0, routes.UserController.editPost()))
        },
        users => {
          if (users.foldRight(true)({ (user, result) => userService.update(user) && result })) {
            eventService.info("EDIT_USER_DONE", s"Utilisateurs modifiés")
            Redirect(routes.UserController.all()).flashing("success" -> "Utilisateurs modifiés")
          } else {
            val form = usersForm.fill(users).withGlobalError("Impossible de mettre à jour certains utilisateurs (Erreur interne)")
            eventService.error("EDIT_USER_ERROR", s"Impossible de modifier des utilisateurs dans la BDD")
            InternalServerError(views.html.editUsers(request.currentUser, request.currentArea)(form, users.length, routes.UserController.editPost()))
          }
        }
      )
    }
  }

  def add = loginAction { implicit request =>
    if(request.currentUser.admin != true) {
      eventService.warn("SHOW_ADD_USER_UNAUTHORIZED", s"Accès non autorisé à l'admin des utilisateurs")
      Unauthorized("Vous n'avez pas le droit de faire ça")
    } else {
      implicit val area = request.currentArea
      val rows = request.getQueryString("rows").map(_.toInt).getOrElse(1)
      eventService.info("EDIT_USER_SHOWED", s"Visualise la vue d'ajouts des utilisateurs")
      Ok(views.html.editUsers(request.currentUser, request.currentArea)(usersForm, rows, routes.UserController.addPost()))
    }
  }

  def addPost = loginAction { implicit request =>
    if(request.currentUser.admin != true) {
      eventService.warn("POST_ADD_USER_UNAUTHORIZED", s"Accès non autorisé à l'admin des utilisateurs")
      Unauthorized("Vous n'avez pas le droit de faire ça")
    } else {
      implicit val area = request.currentArea
      usersForm.bindFromRequest.fold(
        formWithErrors => {
          eventService.error("ADD_USER_ERROR", s"Essai d'ajout d'utilisateurs avec des erreurs de validation")
          BadRequest(views.html.editUsers(request.currentUser, request.currentArea)(formWithErrors, 0, routes.UserController.addPost()))
        },
        users => {
          if (userService.add(users)) {
            eventService.info("ADD_USER_DONE", s"Utilisateurs ajouté")
            Redirect(routes.UserController.all()).flashing("success" -> "Utilisateurs ajouté")
          } else {
            val form = usersForm.fill(users).withGlobalError("Impossible d'ajouté les utilisateurs (Erreur interne)")
            eventService.error("ADD_USER_ERROR", s"Impossible d'ajouter des utilisateurs dans la BDD")
            InternalServerError(views.html.editUsers(request.currentUser, request.currentArea)(form, users.length, routes.UserController.addPost()))
          }
        }
      )
    }
  }

  def showCharte() = loginAction { implicit request =>
    eventService.info("CHARTE_SHOWED", s"Charte visualisé")
    Ok(views.html.showCharte(request.currentUser, request.currentArea))
  }

  def validateCharte() = loginAction { implicit request =>
    Form(
      tuple(
        "redirect" -> optional(text),
        "validate" -> boolean
      )
    ).bindFromRequest.fold(
      formWithErrors => {
        eventService.error("CHARTE_VALIDATION_ERROR", s"Erreur de formulaire dans la validation de la charte")
        BadRequest(s"Formulaire invalide, prévenez l'administrateur du service. ${formWithErrors.errors.mkString(", ")}")
      },
      form => {
        if(form._2) {
          userService.acceptCharte(request.currentUser.id)
        }
        eventService.info("CHARTE_VALIDATED", s"Charte validé")
        form._1 match {
          case Some(redirect) =>
            Redirect(Call("GET", redirect)).flashing("success" -> "Merci d\'avoir accepté la charte")
          case _ =>
            Redirect(routes.ApplicationController.all()).flashing("success" -> "Merci d\'avoir accepté la charte")
        }
      }
    )

  }
}
