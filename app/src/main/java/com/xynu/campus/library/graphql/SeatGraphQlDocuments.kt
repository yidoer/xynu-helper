package com.xynu.campus.library.graphql

/**
 * traceint GraphQL 文档常量。
 *
 * 为什么把文档硬编码进代码：上游 introspection 关闭，schema 不可查，
 * 只能用前端 bundle 里的原始文档串（字段顺序、参数名必须**逐字一致**，
 * 否则服务端会因字段拼写或必填参数缺失报错）。
 *
 * 来源（均为实测抓取，保存在 `app/src/test/resources/fixtures/`）：
 * - `seatMap.chunk.js` → list / libLayout / libRule / reserveSeat / reserueSeat / reserveseat
 * - `ti_prereserve.js` → prereserve / getStep0 / reserve / setStep1 / setStep2 / save / cancle / autoReserve
 *
 * 由 `SeatGraphQlDocumentTest` 逐字比对 fixture 原文，防止手改漂移。
 */
object SeatGraphQlDocuments {

    /** 图书馆列表 + 实时座位数。`libs(libType: -1)` 中的 `-1` 是实测常量，不可变量化。 */
    val LIST = """
        query list {
          userAuth {
            reserve {
              libs(libType: -1) {
                lib_id
                lib_floor
                is_open
                lib_name
                lib_type
                lib_group_id
                lib_comment
                lib_rt {
                  seats_total
                  seats_used
                  seats_booking
                  seats_has
                  reserve_ttl
                  open_time
                  open_time_str
                  close_time
                  close_time_str
                  advance_booking
                }
              }
              libGroups {
                id
                group_name
              }
              reserve {
                isRecordUser
              }
            }
            record {
              libs {
                lib_id
                lib_floor
                is_open
                lib_name
                lib_type
                lib_group_id
                lib_comment
                lib_color_name
                lib_rt {
                  seats_total
                  seats_used
                  seats_booking
                  seats_has
                  reserve_ttl
                  open_time
                  open_time_str
                  close_time
                  close_time_str
                  advance_booking
                }
              }
            }
            rule {
              signRule
            }
            protocol {
              content
            }
          }
        }
    """.trimIndent()

    /** 座位布局（`reserve` 命名空间版本，无 day 参数）。 */
    val LIB_LAYOUT = """
        query libLayout(${'$'}libId: Int, ${'$'}libType: Int) {
          userAuth {
            reserve {
              libs(libType: ${'$'}libType, libId: ${'$'}libId) {
                lib_id
                is_open
                lib_floor
                lib_name
                lib_type
                lib_layout {
                  seats_total
                  seats_booking
                  seats_used
                  max_x
                  max_y
                  seats {
                    x
                    y
                    key
                    type
                    name
                    seat_status
                    status
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    /** 座位布局（`prereserve` 命名空间版本，`$libId: Int!` 必填，字段顺序不同）。 */
    val PRERESERVE_LAYOUT = """
        query libLayout(${'$'}libId: Int!) {
          userAuth {
            prereserve {
              libLayout(libId: ${'$'}libId) {
                max_x
                max_y
                seats_booking
                seats_total
                seats_used
                seats {
                  key
                  name
                  seat_status
                  status
                  type
                  x
                  y
                }
              }
            }
          }
        }
    """.trimIndent()

    /** 座位布局（`goods.reserveseat` 命名空间版本，多 `$day` 参数）。 */
    val RESERVE_LAYOUT = """
        query reserveseat(${'$'}libId: Int!, ${'$'}day: String!) {
          userAuth {
            goods {
              reserveseat {
                libLayout(libId: ${'$'}libId, day: ${'$'}day) {
                  seats_total
                  seats_booking
                  seats_used
                  max_x
                  max_y
                  seats {
                    x
                    y
                    key
                    type
                    name
                    seat_status
                    status
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    /** 图书馆规则（预约提前量、座位时长等）。 */
    val LIB_RULE = """
        query libRule(${'$'}libId: Int!) {
          userAuth {
            reserve {
              libRule(libId: ${'$'}libId) {
                advance_booking
                lib_seat_ttl
                lib_hold_ttl
                lib_renew_time
                hold_reason
                close_start_date
                close_end_date
                open_time
                open_time_str
                close_time
                close_time_str
                lib_validate_time
              }
            }
          }
        }
    """.trimIndent()

    /**
     * 预约座位（`reserve` 命名空间）。
     *
     * `$captcha: String!` 必填：无图形验证码时须传空串 `""`，
     * 不能省略变量，否则服务端报必填参数缺失。
     */
    /**
     * 预约座位。
     *
     * **注意方法名的拼写**：上游 schema 里同时存在 `reserveSeat` 和 `reserueSeat`
     * （只差 `a`/`u` 一个字母），两份文档字段完全相同。
     * 但上游前端真正调用的、以及真正写到座位的那一个是**拼错的那个**
     * `reserueSeat`（见 `seatMap.chunk.js` 的 `reserveSeat:function(t){…
     * i.default.reserueSeat({...})}`）。
     *
     * 我们原来用的是 `reserveSeat`。如果服务端只实现了其中一个，
     * 这条路径就会返回错误或空结果 —— 表现出来就是「座位图能看，点了没反应」。
     * 所以这里对齐上游，用 `reserueSeat`。
     */
    val RESERVE_SEAT = """
        mutation reserueSeat(${'$'}libId: Int!, ${'$'}seatKey: String!, ${'$'}captchaCode: String, ${'$'}captcha: String!) {
          userAuth {
            reserve {
              reserueSeat(
                libId: ${'$'}libId
                seatKey: ${'$'}seatKey
                captchaCode: ${'$'}captchaCode
                captcha: ${'$'}captcha
              )
            }
          }
        }
    """.trimIndent()

    /**
     * 当前预约状态（含到期倒计时字段）。
     *
     * 关键字段：`exp_date` / `exp_date_str` 用于倒计时，
     * `status` 表示当前有无生效预约。
     */
    val RESERVE = """
        query reserve {
          userAuth {
            reserve {
              reserve {
                token
                status
                user_id
                user_nick
                sch_name
                lib_id
                lib_name
                lib_floor
                seat_key
                seat_name
                date
                exp_date
                exp_date_str
                validate_date
                diff
                diff_str
                mark_source
                isRecordUser
                isChooseSeat
                isRecord
                mistakeNum
                openTime
                threshold
                daynum
                hold_date
                mistakeNum
                closeTime
                timerange
              }
              getSToken
            }
          }
        }
    """.trimIndent()

    /** 预约定时状态（自动选座的排队进度）。 */
    val PRERESERVE = """
        query prereserve {
          userAuth {
            prereserve {
              prereserve {
                day
                lib_id
                seat_key
                seat_name
                is_used
                user_mobile
                id
                lib_name
              }
            }
          }
        }
    """.trimIndent()

    /**
     * 定时预约步骤状态（`getStep0`）。
     *
     * 注意 operationName 是 `getStep0`，但文档里的 `query` 名也是 `getStep0`；
     * 与 [GET_STEP] 是两个**完全不同**的查询：
     * - `getStep0` 返回 `getNum` + `captcha{code,data}`（是否需要验证码）
     * - `getStep` 返回流程推进状态与跳转 URL
     */
    val GET_STEP0 = """
        query getStep0 {
          userAuth {
            prereserve {
              getNum
              captcha {
                code
                data
              }
            }
          }
        }
    """.trimIndent()

    /** 定时预约流程状态（排队/成功跳转 URL、结束时间）。 */
    val GET_STEP = """
        query getStep {
          userAuth {
            prereserve {
              getStep
              queeUrl
              successUrl
              endTime
            }
          }
        }
    """.trimIndent()

    /** 步骤 1：提交图形验证码。 */
    val SET_STEP1 = """
        mutation setStep1(${'$'}captcha: String!, ${'$'}captchaCode: String!) {
          userAuth {
            prereserve {
              verifyCaptcha(captcha: ${'$'}captcha, code: ${'$'}captchaCode)
              setStep1
            }
          }
        }
    """.trimIndent()

    /** 步骤 2：用 token 推进。 */
    val SET_STEP2 = """
        mutation setStep2(${'$'}token: String!) {
          userAuth {
            prereserve {
              setStep2(token: ${'$'}token)
            }
          }
        }
    """.trimIndent()

    /** 自动预约（定时抢座）。注意 operationName 拼写是 `autoReserve`。 */
    val AUTO_RESERVE = """
        mutation autoReserve(${'$'}captchaCode: String, ${'$'}captcha: String, ${'$'}libId: Int) {
          userAuth {
            prereserve {
              autoReserve(captcha: ${'$'}captcha, captchaCode: ${'$'}captchaCode, libId: ${'$'}libId)
            }
          }
        }
    """.trimIndent()

    /** 一次验活的最小查询（轻量，只取 lib_id / lib_name）。 */
    val LIB_SIMPLE = """
        query libSimple(${'$'}libId: Int, ${'$'}libType: Int) {
          userAuth {
            reserve {
              libs(libType: ${'$'}libType, libId: ${'$'}libId) {
                lib_id
                is_open
                lib_floor
                lib_name
                lib_type
              }
            }
          }
        }
    """.trimIndent()

    // ---------- operationName 常量（避免手写拼错）----------

    const val OP_LIST = "list"
    const val OP_LIB_LAYOUT = "libLayout"
    const val OP_PRERESERVE_LAYOUT = "libLayout"
    const val OP_RESERVE_LAYOUT = "reserveseat"
    const val OP_LIB_RULE = "libRule"
    /** 预约座位。注意 operationName 拼写是 `reserueSeat`（上游就是拼错的，勿"修正"）。 */
    const val OP_RESERVE_SEAT = "reserueSeat"
    const val OP_RESERVE = "reserve"
    const val OP_PRERESERVE = "prereserve"
    const val OP_GET_STEP0 = "getStep0"
    const val OP_GET_STEP = "getStep"
    const val OP_SET_STEP1 = "setStep1"
    const val OP_SET_STEP2 = "setStep2"
    const val OP_AUTO_RESERVE = "autoReserve"
    const val OP_LIB_SIMPLE = "libSimple"
}
