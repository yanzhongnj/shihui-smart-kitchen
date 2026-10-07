class User {
  final int id;
  final String username;
  final String email;
  final String? nickname;
  final String? avatarUrl;
  final String? role;

  User({
    required this.id,
    required this.username,
    required this.email,
    this.nickname,
    this.avatarUrl,
    this.role,
  });

  bool get isAdmin => role == 'ADMIN';

  factory User.fromJson(Map<String, dynamic> json) {
    return User(
      id: json['id'],
      username: json['username'],
      email: json['email'] ?? '',
      nickname: json['nickname'],
      avatarUrl: json['avatarUrl'],
      role: json['role'] ?? 'USER',
    );
  }
}
