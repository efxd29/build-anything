//! One blocking connection to the bridge app over an abstract Unix socket.

use std::io::{BufRead, BufReader, Write};
use std::os::fd::FromRawFd;
use std::os::unix::net::UnixStream;
use std::time::Duration;

use serde_json::{Value, json};

use super::errors::BridgeError;
use super::protocol::{BridgeRequest, BridgeResponse};

pub const BRIDGE_SOCKET_NAME: &str = "agent-desktop-bridge";
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);

pub struct BridgeConnection {
    reader: BufReader<UnixStream>,
    writer: UnixStream,
    next_id: u64,
}

impl BridgeConnection {
    pub fn connect(timeout: Duration) -> Result<Self, BridgeError> {
        let stream = connect_abstract(BRIDGE_SOCKET_NAME)?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(BridgeError::Io)?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(BridgeError::Io)?;
        let writer = stream.try_clone().map_err(BridgeError::Io)?;
        Ok(Self {
            reader: BufReader::new(stream),
            writer,
            next_id: 0,
        })
    }

    pub fn hello(&mut self, token: &str) -> Result<Value, BridgeError> {
        self.call(
            "hello",
            json!({ "token": token, "client": concat!("agent-desktop-android/", env!("CARGO_PKG_VERSION")) }),
        )
    }

    pub fn call(&mut self, method: &str, params: Value) -> Result<Value, BridgeError> {
        self.next_id += 1;
        let id = self.next_id;
        let request = BridgeRequest {
            id,
            method: method.to_owned(),
            params,
        };
        let mut line = serde_json::to_string(&request)
            .map_err(|error| BridgeError::Protocol(error.to_string()))?;
        line.push('\n');
        self.writer
            .write_all(line.as_bytes())
            .map_err(|error| map_io(error))?;
        let mut response_line = String::new();
        let read = self
            .reader
            .read_line(&mut response_line)
            .map_err(|error| map_io(error))?;
        if read == 0 {
            return Err(BridgeError::Unavailable(
                "bridge closed the connection".to_owned(),
            ));
        }
        let response: BridgeResponse = serde_json::from_str(response_line.trim())
            .map_err(|error| BridgeError::Protocol(error.to_string()))?;
        if response.id != id {
            return Err(BridgeError::Protocol(format!(
                "response id {} does not match request id {id}",
                response.id
            )));
        }
        if response.ok {
            return Ok(response.result.unwrap_or(Value::Null));
        }
        match response.error {
            Some(failure) if failure.code == "token_rejected" => Err(BridgeError::TokenRejected),
            Some(failure) => Err(BridgeError::Remote {
                code: failure.code,
                message: failure.message,
            }),
            None => Err(BridgeError::Protocol(
                "bridge returned an error without detail".to_owned(),
            )),
        }
    }
}

fn map_io(error: std::io::Error) -> BridgeError {
    match error.kind() {
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => BridgeError::Timeout,
        std::io::ErrorKind::ConnectionRefused
        | std::io::ErrorKind::ConnectionReset
        | std::io::ErrorKind::NotFound => BridgeError::Unavailable(error.to_string()),
        _ => BridgeError::Io(error),
    }
}

#[cfg(unix)]
fn connect_abstract(name: &str) -> Result<UnixStream, BridgeError> {
    let name_bytes = name.as_bytes();
    if name_bytes.len() + 1 > 108 {
        return Err(BridgeError::Protocol(
            "bridge socket name is too long".to_owned(),
        ));
    }
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(BridgeError::Io(std::io::Error::last_os_error()));
    }
    let mut address: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    address.sun_family = libc::AF_UNIX as libc::sa_family_t;
    address.sun_path[0] = 0;
    for (index, byte) in name_bytes.iter().enumerate() {
        address.sun_path[index + 1] = *byte as libc::c_char;
    }
    let length = (2 + 1 + name_bytes.len()) as libc::socklen_t;
    let result = unsafe {
        libc::connect(
            fd,
            &address as *const libc::sockaddr_un as *const libc::sockaddr,
            length,
        )
    };
    if result < 0 {
        let error = std::io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(map_io(error));
    }
    Ok(unsafe { UnixStream::from_raw_fd(fd) })
}
