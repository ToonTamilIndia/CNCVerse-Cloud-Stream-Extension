package com.horis.cncverse

data class NewTvOtpResponse(
    val otp: String? = null,
    val status: String? = null,
    val usertoken: String? = null,
    val pub_msg: String? = null,
    val pub_msg_f_size: Int? = null,
    val pub_msg_color: String? = null,
    val error_msg: String? = null
)
