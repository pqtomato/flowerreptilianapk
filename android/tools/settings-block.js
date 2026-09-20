    /* ---------------- 应用设置 ---------------- */
    function fillHourOptions() {
        const select = $('setScheduleHour');
        let html = '';
        for (let hour = 0; hour < 24; hour++) {
            const label = (hour < 10 ? '0' + hour : hour) + ':00';
            html += '<option value="' + hour + '">' + label + '</option>';
        }
        select.innerHTML = html;
    }

    async function loadAppSettings() {
        fillHourOptions();
        try {
            const s = await api('/api/app/settings');
            $('setScheduleEnabled').value = String(s.scheduleEnabled);
            $('setScheduleHour').value = String(s.scheduleHour);
            $('setLookback').value = String(s.lookbackDays);
            $('setFetchDetail').value = String(s.fetchDetail);
            $('setVerifyLink').value = String(s.verifyLink);
            $('appInfo').innerHTML = '数据库：' + escapeHtml(s.dbPath) +
                '<br/>放宽证书校验的域名：' + escapeHtml((s.insecureHosts || []).join('、')) +
                '<br/>提示：采集时会在通知栏显示进度，可以在应用外自动跑完。';
        } catch (e) {
            $('appInfo').textContent = '设置读取失败：' + e.message;
        }
    }

    async function saveAppSettings() {
        const params = new URLSearchParams();
        params.set('scheduleEnabled', $('setScheduleEnabled').value);
        params.set('scheduleHour', $('setScheduleHour').value);
        params.set('lookbackDays', $('setLookback').value);
        params.set('fetchDetail', $('setFetchDetail').value);
        params.set('verifyLink', $('setVerifyLink').value);
        try {
            await api('/api/app/settings?' + params.toString(), { method: 'POST' });
            const enabled = $('setScheduleEnabled').value === 'true';
            $('settingsResult').style.display = '';
            $('settingsResult').innerHTML = '设置已保存：定时采集' + (enabled
                ? '已开启，每天 ' + $('setScheduleHour').value + ':00 自动采集'
                : '已关闭，可在上方手动采集');
            toast('设置已保存');
        } catch (e) {
            toast('保存失败：' + e.message);
        }
    }

    function bindAppSettings() {
        $('btnSaveSettings').addEventListener('click', saveAppSettings);
        $('btnBattery').addEventListener('click', () => {
            if (window.AndroidApp && window.AndroidApp.openBatterySettings) {
                window.AndroidApp.openBatterySettings();
            } else {
                toast('请到 系统设置 → 应用 → 电池 中允许后台运行');
            }
        });
    }
