package com.nekoyu.universe.dashscope_adapter

import java.net.URL

class T2IReq {
    var model: String? = null
    var input: Input = Input()
    var parameters: Parameters = Parameters()

    class Input {
        var messages: ArrayList<Message> = ArrayList()

        public class Message {
            public var role: String? = null
            public var content: ArrayList<Piece> = ArrayList()

            open class Piece

            class Text: Piece {
                var text: String

                constructor(text: String) {
                    this.text = text
                }
            }

            class Image: Piece {
                var image: URL

                constructor(image: URL) {
                    this.image = image
                }
            }
        }
    }

    class Parameters {
        var n: Int = 1
        var negative_prompt: String? = null
        var prompt_extend: Boolean = true
        var watermark: Boolean = false
        var size: String = "2048*2048"
    }
}