/*
 * Copyright 1998-2022 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ru.org.linux.reaction

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.{RequestAttribute, RequestMapping, RequestMethod, RequestParam}
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import ru.org.linux.auth.AccessViolationException
import ru.org.linux.auth.AuthUtil.{AuthorizedOnly, AuthorizedOpt}
import ru.org.linux.comment.{CommentDao, CommentPrepareService}
import ru.org.linux.group.GroupDao
import ru.org.linux.site.Template
import ru.org.linux.topic.{TopicDao, TopicPermissionService, TopicPrepareService}
import ru.org.linux.user.{IgnoreListDao, UserService}

import scala.jdk.CollectionConverters.*

@Controller
@RequestMapping(Array("/reactions"))
class ReactionController(topicDao: TopicDao, commentDao: CommentDao, permissionService: TopicPermissionService,
                         groupDao: GroupDao, userService: UserService, commentPrepareService: CommentPrepareService,
                         ignoreListDao: IgnoreListDao, topicPrepareService: TopicPrepareService,
                         reactionService: ReactionService) {
  @RequestMapping(params = Array("comment"), method = Array(RequestMethod.GET))
  def commentReaction(@RequestAttribute reactionsEnabled: Boolean,
                      @RequestParam("comment") commentId: Int): ModelAndView = AuthorizedOpt { currentUserOpt =>
    val comment = commentDao.getById(commentId)
    val topic = topicDao.getById(comment.topicId)

    currentUserOpt.filter(_ => reactionsEnabled) match {
      case None =>
        new ModelAndView(new RedirectView(topic.getLink + "?cid=" + comment.id))
      case Some(currentUser) =>
        if (!reactionService.allowInteract(currentUser.user, topic, Some(comment))) {
          throw new AccessViolationException("Сообщение не доступно")
        }

        val ignoreList = ignoreListDao.getJava(currentUser.user)

        val tmpl = Template.getTemplate

        new ModelAndView("reaction-comment", Map[String, Any](
          "topic" -> topic,
          "preparedComment" ->
            commentPrepareService.prepareCommentOnly(comment, currentUser.user, tmpl.getProf, topic, ignoreList)
        ).asJava)
    }
  }

  @RequestMapping(params = Array("comment"), method = Array(RequestMethod.POST))
  def setCommentReaction(@RequestAttribute reactionsEnabled: Boolean, @RequestParam("comment") commentId: Int,
                         @RequestParam("reaction") reactionAction: String): ModelAndView = AuthorizedOnly { currentUser =>
    val comment = commentDao.getById(commentId)
    val topic = topicDao.getById(comment.topicId)

    if (reactionsEnabled) {
      if (!reactionService.allowInteract(currentUser.user, topic, None)) {
        throw new AccessViolationException("Сообщение не доступно")
      }

      val Array(reaction, action) = reactionAction.split("-", 2)

      if (!ReactionService.AllowedReactions.contains(reaction)) {
        throw new AccessViolationException("unsupported reaction")
      }

      reactionService.setCommentReaction(comment, currentUser.user, reaction, action=="true")
    }

    new ModelAndView(new RedirectView(topic.getLink + "?cid=" + comment.id))
  }

  @RequestMapping(params = Array("!comment"), method = Array(RequestMethod.GET))
  def topicReaction(@RequestAttribute reactionsEnabled: Boolean,
                    @RequestParam("topic") topicId: Int): ModelAndView = AuthorizedOpt { currentUserOpt =>
    val topic = topicDao.getById(topicId)

    currentUserOpt.filter(_ => reactionsEnabled) match {
      case None =>
        new ModelAndView(new RedirectView(topic.getLink))
      case Some(currentUser) =>
        val group = groupDao.getGroup(topic.groupId)
        val topicAuthor = userService.getUserCached(topic.authorUserId)

        permissionService.checkView(group, topic, currentUser.user, topicAuthor, false)

        if (topic.isDeleted) {
          throw new AccessViolationException("Сообщение не доступно")
        }

        new ModelAndView("reaction-topic", Map(
          "topic" -> topic,
          "preparedTopic" -> topicPrepareService.prepareTopic(topic, currentUser.user)
        ).asJava)
    }
  }

  @RequestMapping(params = Array("!comment"), method = Array(RequestMethod.POST))
  def setTopicReaction(@RequestAttribute reactionsEnabled: Boolean, @RequestParam("topic") topicId: Int,
                         @RequestParam("reaction") reactionAction: String): ModelAndView = AuthorizedOnly { currentUser =>
    val topic = topicDao.getById(topicId)

    if (reactionsEnabled) {
      val group = groupDao.getGroup(topic.groupId)
      val topicAuthor = userService.getUserCached(topic.authorUserId)

      permissionService.checkView(group, topic, currentUser.user, topicAuthor, false)

      if (topic.deleted) {
        throw new AccessViolationException("Сообщение не доступно")
      }

      val Array(reaction, action) = reactionAction.split("-", 2)

      if (!ReactionService.AllowedReactions.contains(reaction)) {
        throw new AccessViolationException("unsupported reaction")
      }

      reactionService.setTopicReaction(topic, currentUser.user, reaction, action == "true")
    }

    new ModelAndView(new RedirectView(topic.getLink))
  }
}
