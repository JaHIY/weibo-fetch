@Grapes(
    [
        @Grab('org.gebish:geb-core'),
        @Grab('org.seleniumhq.selenium:selenium-firefox-driver'),
        @Grab('org.seleniumhq.selenium:selenium-support'),
        @Grab('org.ccil.cowan.tagsoup:tagsoup'),
        @GrabExclude('org.codehaus.groovy:groovy-all'),
    ]
)

import geb.Browser
import geb.Page
import groovy.json.JsonOutput
import groovy.util.XmlParser
import groovy.util.Node
import org.ccil.cowan.tagsoup.Parser

class NeedValidationException extends RuntimeException {
}

class LoginPage extends Page {
    static url = 'http://weibo.com'

    static at = {
        waitFor { $('#pl_login_form') }

        title == '微博-随时随地发现新鲜事'
    }

    static content = {
        loginForm { $('div.W_login_form', 0) }
        usernameField { loginForm.find('input', 0, name: 'username') }
        passwordField { loginForm.find('input', 0, name: 'password') }
        loginButton(to: [HomePage, LoginPage]) { loginForm.find('a', 0, 'action-type': 'btn_submit') }
        verifyCodeBox { loginForm.find('div', 0, 'node-type': 'verifycode_box') }
    }

    void switchToLoginForm() {
        $('#pl_login_form').find('a', 'node-type': 'normal_tab').click()
    }

    void login(String username, String password) {
        this.switchToLoginForm()

        if (verifyCodeBox.displayed) {
            throw new NeedValidationException()
        }

        usernameField.value(username)
        passwordField.value(password)
        loginButton.click()

        if (browser.isAt(this.class)) {
            throw new NeedValidationException()
        }
    }
}

class HomePage extends Page {
    static at = {
        waitFor { $('.home') }

        title == '我的首页 微博-随时随地发现新鲜事'
    }

    static content = {
        profileLink { $('.gn_name') }
        feedList { $('div', 'node-type': 'feed_list') }
    }

    void scrollToBottom() {
        js.exec 'window.scrollTo(0, document.body.scrollHeight);'

        waitFor { feedList.find('div.WB_cardwrap', 'action-type': 'feed_list_item').size() > 15 }

        js.exec '''
            setTimeout(function() {
                window.scrollTo(0, document.body.scrollHeight);
            }, 3000)
        '''

        waitFor { $('.W_pages') }
    }

    String convertContent(String content) {
        def html = new XmlParser(new org.ccil.cowan.tagsoup.Parser()).parseText(content)

        def process = { before, after ->
            def it = before.remove(0)

            if (! (it instanceof groovy.util.Node)) {
                after << it
                return [before, after]
            }

            def attrs = it.attributes()

            switch (it.name().localpart) {
                case 'img':
                    after << attrs['title']
                    return [before, after]
                case 'a':
                    switch (attrs['action-type']) {
                        case 'feed_list_url':
                            after << attrs['href']
                            return [before, after]
                        case 'widget_photoview':
                            after << attrs['alt']
                            return [before, after]
                        default:
                            return [it.children() + before,after]
                    }
                default:
                    return [it.children() + before,after]
            }

        }

        def flatten
        flatten = { before, after = [] ->
            if (before.size() == 0) {
                return after
            }

            flatten.trampoline(*process(before, after))
        }.trampoline()

        flatten(html.body[0].children()).join()
    }

    List getTimeLine() {

        this.scrollToBottom()

        def feedItems = feedList.find('div.WB_cardwrap', 'action-type': 'feed_list_item')

        feedItems.collect { feedItem ->
            def result = { [:].withDefault { owner.call() } }()

            def mainTitle = feedItem.find('.main_title > .WB_type', 0)
            if (mainTitle) {
                result['type'] = mainTitle.text()
            }

            def handleBar = feedItem.find('.WB_feed_handle > .WB_handle', 0)
            def likeCount = handleBar.find('.icon_praised_b', 0).next().text()
            if (likeCount) {
                result['likeCount'] = (likeCount.isLong() ? likeCount : 0) as long
            }

            def forwardCount = handleBar.find('.ficon_forward', 0).next().text()
            if (forwardCount) {
                result['forwardCount'] = (forwardCount.isLong() ? forwardCount : 0) as long
            }

            def favoriteCount = handleBar.find('.ficon_favorite', 0).next().text()
            if (favoriteCount) {
                result['favoriteCount'] = (favoriteCount.isLong() ? favoriteCount : 0) as long
            }

            def commentCount = handleBar.find('.ficon_repeat', 0).next().text()
            if (commentCount) {
                result['commentCount'] = (commentCount.isLong() ? commentCount : 0) as long
            }

            def feedContent = feedItem.find('div', 0, 'node-type': 'feed_content')
            def author = feedContent.find('div.face', 0).find('img', 0)
            result['author']['nickname'] = author.@title
            result['author']['avatarUrl'] = author.@src
            result['author']['profileUrl'] = author.parent().@href
            println result['author']['nickname']

            def publishedTime = feedContent.find('a', 0, 'node-type': 'feed_list_item_date')
            if (publishedTime) {
                result['publishedTime'] = new Date(publishedTime.@date as long)
            }

            def source = feedContent.find('a', 0, 'action-type': 'app_source')
            if (source) {
                result['source'] = source.text()
            }

            def content = feedContent.find('div', 0, 'node-type': 'feed_list_content')
            if (content) {
                result['content']['text'] = this.convertContent(content.@innerHTML)
                println result['content']['text']

                def urlInContent = content.find('a[action-type]')
                if (urlInContent) {
                    result['content']['link'] = urlInContent.collect {
                        switch (it.@'action-type') {
                            case 'widget_photoview':
                                return [
                                    url: it.@alt,
                                    type: 'image',
                                ]
                            case 'feed_list_url':
                                return [
                                    title: it.@title,
                                    url: it.@href,
                                    type: 'page',
                                ]
                        }
                    }
                }

                def topicInContent = content.find('a', 'extra-data': 'type=topic')
                if (topicInContent) {
                    result['content']['topic'] = topicInContent.collect {
                        println it.text()[1..-2]
                        [
                            name: it.text()[1..-2],
                            url: it.@href,
                        ]
                    }
                }

                def mentionedInContent = content.find('a', 'extra-data': 'type=atname')
                if (mentionedInContent) {
                    result['content']['mentioned'] = mentionedInContent.collect {
                        println it.text().substring(1)
                        [
                            nickname: it.text().substring(1),
                            profileUrl: it.@href,
                        ]
                    }
                }
            }

            def forward = feedContent.find('div', 0, 'node-type': 'feed_list_forwardContent')
            if (forward) {
                def forwardAuthor = forward.find('a', 0, 'node-type': 'feed_list_originNick')
                if (forwardAuthor) {
                    result['forward']['author']['nickname'] = forwardAuthor.@'nick-name'
                    result['forward']['author']['profileUrl'] = forwardAuthor.@href
                }

                def fullContent = forward.find('div', 0, 'node-type': 'feed_list_reason_full')
                def normalContent = forward.find('div', 0, 'node-type': 'feed_list_reason')
                def emptyContent = forward.find('.WB_empty', 0)
                def forwardContent = fullContent ? fullContent : (normalContent ? normalContent : emptyContent)
                if (forwardContent) {
                    result['forward']['content']['text'] = this.convertContent(forwardContent.@innerHTML)

                    def urlInForwardContent = forwardContent.find('a[action-type]')
                    if (urlInForwardContent) {
                        result['forward']['content']['link'] = urlInForwardContent.collect {
                            switch (it.@'action-type') {
                                case 'widget_photoview':
                                    return [
                                        url: it.@alt,
                                        type: 'image',
                                    ]
                                case 'feed_list_url':
                                    return [
                                        title: it.@title,
                                        url: it.@href,
                                        type: 'page',
                                    ]
                            }
                        }
                    }

                    def topicInForwardContent = forwardContent.find('a', 'extra-data': 'type=topic')
                    if (topicInForwardContent) {
                        result['forward']['content']['topic'] = topicInForwardContent.collect {
                            println it.text()[1..-2]
                            [
                                name: it.text()[1..-2],
                                url: it.@href,
                            ]
                        }
                    }

                    def mentionedInForwardContent = forwardContent.find('a', 'extra-data': 'type=atname')
                    if (mentionedInForwardContent) {
                        result['forward']['content']['mentioned'] = mentionedInForwardContent.collect {
                            println it.text().substring(1)
                            [
                                nickname: it.text().substring(1),
                                profileUrl: it.@href,
                            ]
                        }
                    }
                }

                def forwardPublishedTime = forward.find('a', 0, 'node-type': 'feed_list_item_date')
                if (forwardPublishedTime) {
                    result['forward']['publishedTime'] = new Date(forwardPublishedTime.@date as long)
                    result['forward']['source'] = forwardPublishedTime.next().text()
                }

                def forwardHandleBar = forward.find('.WB_handle', 0)
                def forwardForwardCount = forwardHandleBar.find('.ficon_forward', 0).next().text()
                if (forwardForwardCount) {
                    result['forward']['forwardCount'] = (forwardForwardCount.isLong() ? forwardForwardCount : 0) as long
                }

                def forwardCommentCount = forwardHandleBar.find('.ficon_repeat', 0).next().text()
                if (forwardCommentCount) {
                    result['forward']['commentCount'] = (forwardCommentCount.isLong() ? forwardCommentCount : 0) as long
                }

                def forwardLikeCount = forwardHandleBar.find('.icon_praised_b', 0).next().text()
                if (forwardLikeCount) {
                    result['forward']['likeCount'] = (forwardLikeCount.isLong() ? forwardLikeCount : 0) as long
                }

                def mediaList = forward.find('div', 0, 'node-type': 'feed_list_media_prev')
                if (mediaList) {
                    def mediaImage = mediaList.find('.WB_pic > img')
                    if (mediaImage) {
                        result['forward']['media']['image'] = mediaImage*.@src
                    }

                    def mediaVideoCoverImage = mediaList.find('.WB_video > img', 0)
                    if (mediaVideoCoverImage) {
                        result['forward']['media']['video']['coverImage'] = mediaVideoCoverImage.@src
                    }
                }
            }

            result
        }
    }
}

def configFile = new File('config.groovy')
def config = new ConfigSlurper().parse(configFile.toURL())

Browser.drive {
    def retryTimes = 3
    while (retryTimes--) {
        try {
            to LoginPage
            login config.username, config.password

            println JsonOutput.prettyPrint(JsonOutput.toJson(getTimeLine()))

            return
        } catch (NeedValidationException e) {
            println 'Retry......'
        }
    }
}
