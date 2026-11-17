package dev.sleepalarm.sensors

enum class SensorAccessReason { ALLOWED, BODY_REQUIRED, BACKGROUND_REQUIRED, BACKGROUND_NOT_GRANTED, INSTALLER_RESTRICTED }

data class SensorAccess(val reason: SensorAccessReason, val explanation: String) {
    val permitted: Boolean get() = reason == SensorAccessReason.ALLOWED
    val canRequestBackground: Boolean get() = reason == SensorAccessReason.BACKGROUND_REQUIRED || reason == SensorAccessReason.BACKGROUND_NOT_GRANTED
}

/** Only report facts the public APIs expose. A dismissed settings page is not proof of policy denial. */
object SensorAccessPolicy {
    fun evaluate(api: Int, bodyGranted: Boolean, backgroundGranted: Boolean, backgroundRequested: Boolean,
                 restrictedAndNotAllowlisted: Boolean?): SensorAccess {
        if (!bodyGranted) return SensorAccess(SensorAccessReason.BODY_REQUIRED,
            "심박 센서 권한이 없습니다. 먼저 ‘1. 심박 센서 허용’을 진행한 다음 백그라운드 권한을 별도로 설정하세요. 일반 알람은 사용할 수 있습니다.")
        if (api < 33 || backgroundGranted) return SensorAccess(SensorAccessReason.ALLOWED,
            "백그라운드 심박 접근이 허용되어 있습니다. 실제 센서 지원과 데이터 품질은 별도로 확인합니다.")
        if (restrictedAndNotAllowlisted == true) return SensorAccess(SensorAccessReason.INSTALLER_RESTRICTED,
            "설치자 허용 목록에 없는 제한된 백그라운드 센서 권한입니다. 앱에서 허용하거나 우회할 수 없습니다. 설치 경로 또는 기기 관리 정책을 확인하세요. 심박 보조는 사용할 수 없습니다. 움직임 분석 권한을 허용하면 움직임만 사용합니다.")
        val detail = if (restrictedAndNotAllowlisted == null)
            "설치자 제한 여부는 이 앱에서 확인할 수 없습니다. ‘항상 허용’이 없거나 비활성화되어 있으면 설치자·기기 관리 정책으로 제한되었을 수 있습니다."
        else "설치자 제한으로 차단된 상태는 아니지만 사용자 백그라운드 허용이 완료되지 않았습니다."
        return SensorAccess(
            if (backgroundRequested) SensorAccessReason.BACKGROUND_NOT_GRANTED else SensorAccessReason.BACKGROUND_REQUIRED,
            (if (backgroundRequested) "요청 후에도 백그라운드 센서 권한이 허용되지 않았습니다. 거부 또는 설정 미완료 상태입니다. "
             else "백그라운드 센서 권한을 아직 허용하지 않았습니다. ") +
                "‘2. 센서 항상 허용’ 또는 ‘앱 시스템 설정’ → 권한 → 신체 센서에서 ‘항상 허용’을 선택하세요. " + detail +
                " 심박 미허용 시 움직임 분석 권한으로 움직임만 사용할 수 있습니다. 두 권한 경로가 모두 없으면 일반 알람을 사용합니다.",
        )
    }
}
