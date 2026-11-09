#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
asset_decrypt_deployer.py

안드로이드 모바일 보안 솔루션(LIAPP / XShield)으로 암호화된 에셋(IL2CPP 메타데이터 및 동적 카카오 SDK DEX)을
16바이트 XOR 알고리즘으로 복호화하고, 타깃 경로(실제 기기 경로 및 VirtualApp 가상 샌드박스 내부)로
자동 배치 및 권한(Android 14+ W^X 대응 chmod 0444)을 설정하는 자동화 도구.

주요 구현 기능:
1. 16바이트 주기적 XOR 복호화 엔진 (암호화 페이로드 -> 평문 복원)
2. 헤더 시그니처 기반 16바이트 키 복원/검증 (IL2CPP 0xFAB11BAF, DEX dex\n035\0)
3. APK 내 에셋 직접 추출 또는 디바이스 adb 연동 추출
4. 복호화된 IL2CPP 메타데이터 (files/il2cpp/Metadata/global-metadata.dat) 자동 배치
5. 복호화된 카카오 SDK DEX (code_cache/liapp-y.dex) 자동 주입 및 chmod 0444 설정
"""

import os
import sys
import argparse
import subprocess
import zipfile
import struct

# IL2CPP Metadata 헤더 매직 (0xFAB11BAF -> Little Endian: AF 1B B1 FA)
IL2CPP_MAGIC = bytes([0xAF, 0x1B, 0xB1, 0xFA])
# DEX 헤더 매직
DEX_MAGIC = b'dex\n035\x00'
DEX_MAGIC_039 = b'dex\n039\x00'

DEFAULT_ADB = r"E:\devtools\android-sdk\platform-tools\adb.exe"

def run_adb(cmd_args, adb_path=DEFAULT_ADB, check=True):
    """ADB 명령어 실행 헬퍼"""
    full_cmd = [adb_path] + cmd_args
    res = subprocess.run(full_cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if check and res.returncode != 0:
        print(f"[ADB ERROR] Command failed: {' '.join(full_cmd)}\nStderr: {res.stderr.strip()}")
    return res

def xor_decrypt(data: bytes, key: bytes) -> bytes:
    """16바이트 주기적 XOR 복호화 루틴"""
    if not key or len(key) == 0:
        raise ValueError("Key must not be empty")
    key_len = len(key)
    out = bytearray(len(data))
    for i in range(len(data)):
        out[i] = data[i] ^ key[i % key_len]
    return bytes(out)

def derive_key_from_known_header(encrypted_data: bytes, known_header: bytes, key_length: int = 16) -> bytes:
    """
    알려진 헤더 매직 바이트를 기반으로 16바이트 키의 앞부분 또는 전체를 도출/추정.
    """
    if len(encrypted_data) < len(known_header):
        raise ValueError("Encrypted data is shorter than known header")
    partial_key = bytearray(key_length)
    for i in range(min(len(known_header), key_length)):
        partial_key[i] = encrypted_data[i] ^ known_header[i]
    return bytes(partial_key)

def extract_asset_from_apk(apk_path: str, asset_rel_path: str) -> bytes:
    """APK(ZIP) 아카이브에서 특정 에셋 파일 바이트 추출"""
    with zipfile.ZipFile(apk_path, 'r') as z:
        norm_path = asset_rel_path.replace('\\', '/')
        if norm_path not in z.namelist():
            raise FileNotFoundError(f"Asset '{norm_path}' not found in APK {apk_path}")
        return z.read(norm_path)

def deploy_to_device(local_file_path: str, remote_dest_path: str, is_dex: bool = False,
                     run_as_pkg: str = None, adb_path: str = DEFAULT_ADB):
    """
    디바이스의 특정 경로에 파일 복사 및 권한 설정.
    run_as_pkg가 주어지면 run-as를 활용하여 앱 내부 샌드박스로 전송.
    """
    print(f"[*] Deploying {local_file_path} -> {remote_dest_path}...")
    temp_remote = "/data/local/tmp/" + os.path.basename(local_file_path)

    # 1. 임시 디렉터리로 푸시
    run_adb(["push", local_file_path, temp_remote], adb_path=adb_path)

    # 2. 타깃 경로의 부모 디렉터리 생성 및 이동
    remote_dir = os.path.dirname(remote_dest_path).replace('\\', '/')
    dest_path = remote_dest_path.replace('\\', '/')

    if run_as_pkg:
        cmd_mkdir = f"run-as {run_as_pkg} mkdir -p {remote_dir}"
        cmd_cp = f"run-as {run_as_pkg} cp {temp_remote} {dest_path}"
        run_adb(["shell", cmd_mkdir], adb_path=adb_path, check=False)
        run_adb(["shell", cmd_cp], adb_path=adb_path)

        if is_dex:
            # Android 14+ W^X 보안: DEX 파일은 반드시 쓰기 금지(0444) 설정 필요
            cmd_chmod = f"run-as {run_as_pkg} chmod 0444 {dest_path}"
            run_adb(["shell", cmd_chmod], adb_path=adb_path)
            print(f"[+] Applied chmod 0444 to DEX: {dest_path}")
        else:
            cmd_chmod = f"run-as {run_as_pkg} chmod 0644 {dest_path}"
            run_adb(["shell", cmd_chmod], adb_path=adb_path)
    else:
        # 일반 외부 저장소(/sdcard/...) 등
        run_adb(["shell", f"mkdir -p {remote_dir}"], adb_path=adb_path, check=False)
        run_adb(["shell", f"cp {temp_remote} {dest_path}"], adb_path=adb_path)
        if is_dex:
            run_adb(["shell", f"chmod 0444 {dest_path}"], adb_path=adb_path)
        else:
            run_adb(["shell", f"chmod 0644 {dest_path}"], adb_path=adb_path)

    # 3. 임시 파일 정리
    run_adb(["shell", f"rm -f {temp_remote}"], adb_path=adb_path, check=False)
    print(f"[+] Deployment complete: {dest_path}")

def parse_args():
    parser = argparse.ArgumentParser(description="LIAPP / XShield Asset XOR Decryptor & Auto Deployer")
    parser.add_argument("--apk", help="Path to APK file. If omitted, will be queried via ADB.")
    parser.add_argument("--pkg", default="com.kakaogames.dokkaebi", help="Target package name (default: com.kakaogames.dokkaebi)")
    parser.add_argument("--host-pkg", default="io.vax.host", help="VirtualApp host package name (default: io.vax.host)")
    parser.add_argument("--meta-asset", default="assets/z/8f3d70a1", help="Asset path for IL2CPP metadata")
    parser.add_argument("--dex-asset", default="assets/z/c4a19e2b", help="Asset path for Kakao SDK DEX")
    parser.add_argument("--key", help="16-byte XOR key in hex format (e.g. 0102030405060708090a0b0c0d0e0f10) or ASCII")
    parser.add_argument("--out-dir", default="decrypted_assets", help="Local directory to save decrypted files")
    parser.add_argument("--deploy", action="store_true", help="Deploy decrypted assets to target device via ADB")
    parser.add_argument("--adb", default=DEFAULT_ADB, help="Path to adb executable")
    return parser.parse_args()

def main():
    args = parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    # 1. 16바이트 키 파싱
    key_bytes = None
    if args.key:
        if len(args.key) == 32:
            try:
                key_bytes = bytes.fromhex(args.key)
            except ValueError:
                key_bytes = args.key.encode('utf-8')[:16]
        else:
            key_bytes = args.key.encode('utf-8')[:16]
            if len(key_bytes) < 16:
                key_bytes = key_bytes.ljust(16, b'\x00')
        print(f"[*] Using provided 16-byte key: {key_bytes.hex()}")

    # 2. APK 파일 확보
    apk_path = args.apk
    if not apk_path:
        print(f"[*] Querying APK path on device for package '{args.pkg}'...")
        res = run_adb(["shell", f"pm path {args.pkg}"], adb_path=args.adb)
        for line in res.stdout.splitlines():
            if "base.apk" in line:
                remote_apk = line.split(":", 1)[1].strip()
                local_apk = os.path.join(args.out_dir, "base.apk")
                if not os.path.exists(local_apk):
                    print(f"[*] Pulling {remote_apk} -> {local_apk}...")
                    run_adb(["pull", remote_apk, local_apk], adb_path=args.adb)
                apk_path = local_apk
                break

    if not apk_path or not os.path.exists(apk_path):
        print(f"[!] Target APK could not be found or pulled.")
        sys.exit(1)

    print(f"[*] Analyzing APK: {apk_path}")

    # 3. IL2CPP 메타데이터 복호화 처리
    try:
        raw_meta = extract_asset_from_apk(apk_path, args.meta_asset)
        print(f"[*] Successfully extracted {args.meta_asset} ({len(raw_meta)} bytes)")

        # 키가 명시되지 않은 경우 IL2CPP 매직 시그니처로부터 키 역산 시도
        if not key_bytes:
            inferred = derive_key_from_known_header(raw_meta, IL2CPP_MAGIC, 16)
            print(f"[?] Inferred partial key from IL2CPP magic: {inferred[:4].hex()}...")
            key_bytes = inferred

        dec_meta = xor_decrypt(raw_meta, key_bytes)
        if dec_meta.startswith(IL2CPP_MAGIC):
            print(f"[+] IL2CPP Metadata successfully validated with magic 0x{dec_meta[:4].hex()}!")
        else:
            print(f"[!] Warning: Decrypted metadata header does not match standard 0xFAB11BAF (Header: {dec_meta[:4].hex()})")

        out_meta_path = os.path.join(args.out_dir, "global-metadata.dat")
        with open(out_meta_path, "wb") as f:
            f.write(dec_meta)
        print(f"[+] Saved decrypted metadata to: {out_meta_path}")

        if args.deploy:
            # 외부 저장소 경로에 배포
            sd_dest = f"/sdcard/Android/data/{args.pkg}/files/il2cpp/Metadata/global-metadata.dat"
            deploy_to_device(out_meta_path, sd_dest, is_dex=False, adb_path=args.adb)

            # VirtualApp 가상 컨테이너 내부 경로에 배포
            va_dest = f"/data/data/{args.host_pkg}/virtual/data/user/0/{args.pkg}/files/il2cpp/Metadata/global-metadata.dat"
            deploy_to_device(out_meta_path, va_dest, is_dex=False, run_as_pkg=args.host_pkg, adb_path=args.adb)

    except FileNotFoundError:
        print(f"[-] Asset {args.meta_asset} not found in APK. Checking fallback standard paths...")
        try:
            raw_meta = extract_asset_from_apk(apk_path, "assets/bin/Data/Managed/Metadata/global-metadata.dat")
            print(f"[*] Found standard IL2CPP metadata ({len(raw_meta)} bytes). Magic: {raw_meta[:4].hex()}")
            out_meta_path = os.path.join(args.out_dir, "global-metadata.dat")
            with open(out_meta_path, "wb") as f:
                f.write(raw_meta)
            if args.deploy:
                va_dest = f"/data/data/{args.host_pkg}/virtual/data/user/0/{args.pkg}/files/il2cpp/Metadata/global-metadata.dat"
                deploy_to_device(out_meta_path, va_dest, is_dex=False, run_as_pkg=args.host_pkg, adb_path=args.adb)
        except Exception as e:
            print(f"[-] Fallback metadata check failed: {e}")

    # 4. 카카오 SDK DEX 복호화 처리
    try:
        raw_dex = extract_asset_from_apk(apk_path, args.dex_asset)
        print(f"[*] Successfully extracted {args.dex_asset} ({len(raw_dex)} bytes)")

        dec_dex = xor_decrypt(raw_dex, key_bytes)
        if dec_dex.startswith(b'dex\n'):
            print(f"[+] DEX successfully validated with magic: {dec_dex[:8]}")
        else:
            print(f"[!] Warning: Decrypted DEX header does not match DEX magic: {dec_dex[:8]}")

        out_dex_path = os.path.join(args.out_dir, "liapp-y.dex")
        with open(out_dex_path, "wb") as f:
            f.write(dec_dex)
        print(f"[+] Saved decrypted DEX to: {out_dex_path}")

        if args.deploy:
            # VirtualApp code_cache 디렉터리에 주입 및 chmod 0444 적용
            va_code_cache_dex = f"/data/data/{args.host_pkg}/virtual/data/user/0/{args.pkg}/code_cache/liapp-y.dex"
            deploy_to_device(out_dex_path, va_code_cache_dex, is_dex=True, run_as_pkg=args.host_pkg, adb_path=args.adb)

    except FileNotFoundError:
        print(f"[-] Asset {args.dex_asset} not found in APK. Skipping DEX deployment.")

    print("[*] All tasks completed successfully.")

if __name__ == "__main__":
    main()
