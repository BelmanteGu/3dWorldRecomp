// Online services of Super Mario 3D World (Miiverse, SpotPass sockets, curl, SSL): the game is never online, so
// each of these answers "unavailable". They are reached only inside the network code, after an initialize or a
// connect that has already failed; having an explicit answer keeps them out of the "unimplemented" log.
// Generated from the game's import list (tools/recomp: imports.json); the answers by hand.
#include "../runtime.h"

static constexpr uint32_t kResultFail = 0xA0000000;  // nn::Result: bit 31 set = failure
static constexpr uint32_t kMinusOne = 0xFFFFFFFFu;

HLE(nlibcurl, curl_easy_cleanup) { ret(c, 0); }
HLE(nlibcurl, curl_easy_init) { ret(c, 0); }
HLE(nlibcurl, curl_easy_pause) { ret(c, 0); }
HLE(nlibcurl, curl_easy_setopt) { ret(c, 0); }
HLE(nlibcurl, curl_formfree) { ret(c, 0); }
HLE(nlibcurl, curl_multi_add_handle) { ret(c, 0); }
HLE(nlibcurl, curl_multi_cleanup) { ret(c, 0); }
HLE(nlibcurl, curl_multi_fdset) { ret(c, 0); }
HLE(nlibcurl, curl_multi_info_read) { ret(c, 0); }
HLE(nlibcurl, curl_multi_init) { ret(c, 0); }
HLE(nlibcurl, curl_multi_perform) { ret(c, 0); }
HLE(nlibcurl, curl_multi_remove_handle) { ret(c, 0); }
HLE(nlibcurl, curl_multi_timeout) { ret(c, 0); }
HLE(nlibcurl, curl_slist_append) { ret(c, 0); }
HLE(nlibcurl, curl_slist_free_all) { ret(c, 0); }
HLE(nn_olv, AddStampData__Q3_2nn3olv28UploadPostDataByPostAppParamFPCUcUi) { ret(c, 0); }
HLE(nn_olv, __ct__Q3_2nn3olv15InitializeParamFv) { ret(c, 0); }
HLE(nn_olv, __ct__Q3_2nn3olv19StartPortalAppParamFv) { ret(c, 0); }
HLE(nn_olv, __ct__Q3_2nn3olv25DownloadPostDataListParamFv) { ret(c, 0); }
HLE(nn_olv, __ct__Q3_2nn3olv28UploadEmpathyToPostDataParamFv) { ret(c, 0); }
HLE(nn_olv, __ct__Q3_2nn3olv28UploadPostDataByPostAppParamFv) { ret(c, 0); }
HLE(nn_olv, DownloadPostDataList__Q2_2nn3olvFPQ3_2nn3olv19DownloadedTopicDataPQ3_2nn3olv18DownloadedPostDataPUiUiPCQ3_2nn3olv25DownloadPostDataListParam) { ret(c, kResultFail); }
HLE(nn_olv, __dt__Q3_2nn3olv28UploadPostDataByPostAppParamFv) { ret(c, 0); }
HLE(nn_olv, GetAppData__Q3_2nn3olv18DownloadedDataBaseCFPUcPUiUi) { ret(c, 0); }
HLE(nn_olv, GetAppDataSize__Q3_2nn3olv18DownloadedDataBaseCFv) { ret(c, 0); }
HLE(nn_olv, GetBodyMemo__Q3_2nn3olv18DownloadedDataBaseCFPUcPUiUi) { ret(c, 0); }
HLE(nn_olv, GetBodyText__Q3_2nn3olv18DownloadedDataBaseCFPwUi) { ret(c, 0); }
HLE(nn_olv, GetErrorCode__Q2_2nn3olvFRCQ2_2nn6Result) { ret(c, 0); }
HLE(nn_olv, GetFeeling__Q3_2nn3olv18DownloadedDataBaseCFv) { ret(c, 0); }
HLE(nn_olv, GetMiiData__Q3_2nn3olv18DownloadedDataBaseCFP12FFLStoreData) { ret(c, 0); }
HLE(nn_olv, GetMiiNickname__Q3_2nn3olv18DownloadedDataBaseCFv) { ret(c, 0); }
HLE(nn_olv, GetPostId__Q3_2nn3olv18DownloadedPostDataCFv) { ret(c, 0); }
HLE(nn_olv, GetTopicTag__Q3_2nn3olv18DownloadedDataBaseCFv) { ret(c, 0); }
HLE(nn_olv, GetUserPid__Q3_2nn3olv18DownloadedDataBaseCFv) { ret(c, 0); }
HLE(nn_olv, SetAppData__Q3_2nn3olv15UploadParamBaseFPCUcUi) { ret(c, 0); }
HLE(nn_olv, SetFlags__Q3_2nn3olv25DownloadPostDataListParamFUi) { ret(c, 0); }
HLE(nn_olv, SetFlags__Q3_2nn3olv28UploadPostDataByPostAppParamFUi) { ret(c, 0); }
HLE(nn_olv, SetPostDataMaxNum__Q3_2nn3olv25DownloadPostDataListParamFUi) { ret(c, 0); }
HLE(nn_olv, SetPostId__Q3_2nn3olv19StartPortalAppParamFPCc) { ret(c, 0); }
HLE(nn_olv, SetPostId__Q3_2nn3olv28UploadEmpathyToPostDataParamFPCc) { ret(c, 0); }
HLE(nn_olv, SetSearchKey__Q3_2nn3olv19UploadPostDataParamFPCwUc) { ret(c, 0); }
HLE(nn_olv, SetSearchKey__Q3_2nn3olv25DownloadPostDataListParamFPCw) { ret(c, 0); }
HLE(nn_olv, SetTopicTag__Q3_2nn3olv19UploadPostDataParamFPCw) { ret(c, 0); }
HLE(nn_olv, SetWork__Q3_2nn3olv15InitializeParamFPUcUi) { ret(c, 0); }
HLE(nn_olv, SetWork__Q3_2nn3olv28UploadPostDataByPostAppParamFPUcUi) { ret(c, 0); }
HLE(nn_olv, StartPortalApp__Q2_2nn3olvFPCQ3_2nn3olv19StartPortalAppParam) { ret(c, kResultFail); }
HLE(nn_olv, TestFlags__Q3_2nn3olv18DownloadedDataBaseCFUi) { ret(c, 0); }
HLE(nn_olv, UploadEmpathyToPostData__Q2_2nn3olvFPCQ3_2nn3olv28UploadEmpathyToPostDataParam) { ret(c, kResultFail); }
HLE(nn_olv, UploadPostDataByPostApp__Q2_2nn3olvFPCQ3_2nn3olv28UploadPostDataByPostAppParam) { ret(c, kResultFail); }
HLE(nsysnet, bind) { ret(c, kMinusOne); }
HLE(nsysnet, gethostbyname) { ret(c, 0); }
HLE(nsysnet, getsockname) { ret(c, kMinusOne); }
HLE(nsysnet, htonl) { ret(c, arg(c, 0)); }
HLE(nsysnet, htons) { ret(c, arg(c, 0) & 0xFFFF); }
HLE(nsysnet, inet_ntoa) { ret(c, 0); }
HLE(nsysnet, inet_pton) { ret(c, kMinusOne); }
HLE(nsysnet, NSSLAddServerPKIGroups) { ret(c, 0); }
HLE(nsysnet, NSSLCreateContext) { ret(c, kMinusOne); }
HLE(nsysnet, NSSLDestroyContext) { ret(c, 0); }
HLE(nsysnet, ntohl) { ret(c, arg(c, 0)); }
HLE(nsysnet, ntohs) { ret(c, arg(c, 0) & 0xFFFF); }
HLE(nsysnet, recv) { ret(c, kMinusOne); }
HLE(nsysnet, recvfrom) { ret(c, kMinusOne); }
HLE(nsysnet, select) { ret(c, kMinusOne); }
HLE(nsysnet, send) { ret(c, kMinusOne); }
HLE(nsysnet, sendto) { ret(c, kMinusOne); }
HLE(nsysnet, setsockopt) { ret(c, kMinusOne); }
HLE(nsysnet, shutdown) { ret(c, kMinusOne); }
HLE(nsysnet, socket) { ret(c, kMinusOne); }
HLE(nsysnet, socketclose) { ret(c, 0); }
HLE(nsysnet, socketlasterr) { ret(c, 0); }
