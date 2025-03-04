package model;

import java.security.InvalidParameterException;

public class FileListResponseType extends ResponseType {
	private FileDescriptor[] files = null;

	public FileListResponseType(int responseType, int file_id, long start_byte, long end_byte, byte[] data) {
		super(responseType, file_id, start_byte, end_byte, data);
		setFileDescriptors();
	}

	public FileListResponseType(byte[] rawData) {
		super(rawData);
		setFileDescriptors();
	}

	private void setFileDescriptors() {
		files = new FileDescriptor[this.getFile_id()];
		byte[] data = this.getData();

		int foundFiles = 0;
		int dataIndex = 0;
		while (foundFiles < files.length && dataIndex < data.length) {
			int file_id = ((int) data[dataIndex] & 0xFF);
			dataIndex++;
			StringBuffer file_name = new StringBuffer();
			while (dataIndex < data.length && data[dataIndex] != '\0') {
				file_name.append((char) data[dataIndex]);
				dataIndex++;
			}
			files[foundFiles] = new FileDescriptor(file_id, file_name.toString());
			foundFiles++;
			dataIndex++;
		}

		if (foundFiles != files.length) {
			throw new InvalidParameterException("Number of files does not match with found files");
		}
	}

	public FileDescriptor[] getFileDescriptors() {
		return files;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer("\nresponse_type:" + this.getResponseType());
		sb.append("\nfile_id:" + this.getFile_id());
		sb.append("\nstart_byte:" + this.getStart_byte());
		sb.append("\nend_byte:" + this.getEnd_byte());
		sb.append("\ndata:");
		for (FileDescriptor file : getFileDescriptors()) {
			sb.append("\n" + file.toString());
		}
		return sb.toString();
	}

}
